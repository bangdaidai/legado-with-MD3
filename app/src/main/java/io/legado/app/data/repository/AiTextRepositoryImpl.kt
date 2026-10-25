package io.legado.app.data.repository

import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.ai.AiOutputCeilings
import io.legado.app.data.repository.ai.AiProtocolHandler
import io.legado.app.data.repository.ai.AiProviderRegistry
import io.legado.app.data.repository.ai.AnthropicHandler
import io.legado.app.data.repository.ai.OpenAiChatHandler
import io.legado.app.data.repository.ai.OpenAiResponsesHandler
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.model.AiAvailableModel
import io.legado.app.domain.model.AiCallTrace
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerateResponse
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.aiTaskSceneLabel
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.RecordingTrace
import io.legado.app.help.config.AppConfigStore
import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
import io.legado.app.data.repository.ai.formatAiPromptForLog
import io.legado.app.data.repository.ai.truncateForLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AiTextRepositoryImpl(
    private val aiLogRepository: AiLogRepository,
) : AiTextGateway {

    private val registry = AiProviderRegistry(
        handlers = listOf(
            OpenAiChatHandler(),
            OpenAiResponsesHandler(),
            AnthropicHandler()
        )
    )

    private fun callTimeoutMs(): Long {
        // 默认 180s：听书整章分析（几十分段 + 严格 JSON 输出）实测常要 50-60s，
        // 默认 60s 会把正常的长分析掐成「请求超时」。超时只是上限，不拖慢快请求。
        val seconds = AppConfigStore.getInt(PreferKey.aiCallTimeout) ?: 180
        return seconds.coerceIn(5, 600) * 1000L
    }

    override suspend fun generate(
        request: AiGenerateRequest
    ): Result<AiGenerateResponse> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val recording = RecordingTrace(start)

        var response: AiGenerateResponse? = null
        var error: String? = null
        // 保留原始异常供 Result.failure 返回。之前这里统一包成 RuntimeException，
        // AiHttpException(429) 等类型信息全部丢失，下游 aiFailureKind() 只能按 message
        // 猜，限流/超时分类全部失效。
        var failure: Throwable? = null
        var cancellation: CancellationException? = null
        var cancelReason: String? = null
        try {
            response = withContext(Dispatchers.IO) {
                withTimeout(callTimeoutMs()) {
                    generateWithinOutputCeiling(
                        handler = registry.handlerFor(provider.protocol),
                        request = request,
                        trace = recording,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            error = "请求超时"
            failure = e
        } catch (e: CancellationException) {
            cancellation = e
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
            failure = e
        }

        if (cancellation != null) {
            // cancel 点带的人话原因（如"被新一轮起播取代"）会被协程包成
            // "xx was cancelled" 的 JobCancellationException，原因本身挂在 cause 上，
            // 只读最外层消息会把标了原因的取消也显示成"上层未标注原因"
            cancelReason = cancellationReason(cancellation) ?: "上层未标注原因"
        }

        withContext(NonCancellable) {
            aiLogRepository.record(
                AiLogEntry(
                    timeMillis = start,
                    kind = "generate",
                    providerName = provider.name,
                    providerProtocol = provider.protocol,
                    modelId = model.modelId,
                    modelDisplayName = model.displayName,
                    summary = logConclusion(
                        taskType = request.taskType,
                        cancelled = cancellation != null,
                        error = if (cancellation != null) cancelReason else error,
                        outputChars = response?.text?.length ?: 0,
                        hasReasoning = !response?.reasoning.isNullOrBlank(),
                    ),
                    success = cancellation == null && error == null,
                    // 取消单独一档：超时在上面已经按「请求超时」归到失败，不会走到这里
                    cancelled = cancellation != null,
                    durationMillis = System.currentTimeMillis() - start,
                    error = if (cancellation != null) cancelReason else error,
                    scenario = listOfNotNull(
                        aiTaskSceneLabel(request.taskType),
                        request.sourceLabel,
                    ).joinToString(" · "),
                    steps = recording.steps,
                )
            )
        }

        if (cancellation != null) throw cancellation
        return response?.let { Result.success(it) }
            ?: Result.failure(failure ?: RuntimeException(error ?: "AI 生成失败"))
    }

    /**
     * 发请求前按已知上限夹住输出预算；被供应商以「max_tokens 非法」打回时，照它报出来的
     * 数字降一档重发，最多 [MAX_OUTPUT_CEILING_FALLBACKS] 档。
     *
     * 为什么接在仓库层：预算是上层按「这次要判多少个分段」放大出来的（必须放大——预算不够
     * 输出会断尾，严格 JSON 解析必炸），而那条线画在哪里只有供应商自己知道。不接这一手的
     * 实际后果是 2026-10-25 那条日志：智谱对 GLM-4-Flash 只允许 16384，第 14 章的分镜说话人
     * 分析带着 2 万多直接 400，整章失败。
     */
    private suspend fun generateWithinOutputCeiling(
        handler: AiProtocolHandler,
        request: AiGenerateRequest,
        trace: AiCallTrace,
    ): AiGenerateResponse {
        val modelKey = AiOutputCeilings.keyFor(request.model)
        var current = clampToKnownCeiling(request, trace)
        var loweredTo: Int? = null
        var fallbacks = 0
        while (true) {
            val outcome = runCatching { handler.generate(current, trace).getOrThrow() }
            val error = outcome.exceptionOrNull()
            if (error == null) {
                // 学到的上限要等重发真的跑通才留下：万一读错数字，把 16384 记成 4096
                // 会让这个模型之后每次都被小预算卡住
                loweredTo?.let { AiOutputCeilings.remember(modelKey, it) }
                return outcome.getOrThrow()
            }
            // 取消不是失败，交给外层按「调用被取消」落日志
            if (error is CancellationException) throw error
            if (fallbacks >= MAX_OUTPUT_CEILING_FALLBACKS ||
                !AiOutputCeilings.isMaxTokensRejection(error)
            ) {
                throw error
            }
            val budget = current.params.maxOutputTokens ?: 0
            val target = AiOutputCeilings.lowerAfterRejection(
                current = budget,
                rejectionText = AiOutputCeilings.rejectionText(error),
            ) ?: throw error
            trace.mark("供应商不接受 $budget 的输出预算，降到 $target 重发")
            current = current.copy(params = current.params.copy(maxOutputTokens = target))
            loweredTo = target
            fallbacks++
        }
    }

    /**
     * 知道这个模型允许输出多少（用户在模型编辑页填的「最大输出 tokens」优先，其次本机
     * 上一次学到的）就把预算夹进去，并在过程日志留一行，免得用户以为预算真的按上面算的发出去了。
     */
    private fun clampToKnownCeiling(
        request: AiGenerateRequest,
        trace: AiCallTrace,
    ): AiGenerateRequest {
        val budget = request.params.maxOutputTokens ?: return request
        val ceiling = AiOutputCeilings.ceilingOf(
            key = AiOutputCeilings.keyFor(request.model),
            declaredLimit = request.model.maxOutputTokens,
        ) ?: return request
        if (budget <= ceiling) return request
        trace.mark("输出预算 $budget 超过模型上限 $ceiling，已按 $ceiling 发")
        return AiOutputCeilings.clampTo(request, ceiling)
    }

    override fun generateStream(
        request: AiGenerateRequest
    ): Flow<AiStreamEvent> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val recording = RecordingTrace(start)
        val suppressLog = request.suppressLog
        // 流式这条路只做「发之前夹住」，不做被拒后降档重发：重发要把 emit 包进 try，
        // 连协程取消一起判，代价大于收益。上限一旦由非流式调用学到，这里下次自然受益。
        val preparedRequest = clampToKnownCeiling(request, recording)
        // 事件在发给上层的同时留一份聚合，调用结束后写进 AI 日志
        val reasoningBuilder = StringBuilder()
        val outputBuilder = StringBuilder()
        return flow {
            registry.handlerFor(provider.protocol).stream(preparedRequest, { event ->
                when (event) {
                    is AiStreamEvent.Reasoning -> reasoningBuilder.append(event.text)
                    is AiStreamEvent.Content -> outputBuilder.append(event.text)
                    else -> Unit
                }
                emit(event)
            }, recording)
        }.flowOn(Dispatchers.IO)
            .onCompletion { cause ->
                if (suppressLog) return@onCompletion
                val success = cause == null
                // 与 generate 一致：withTimeout 造的超时是 CancellationException 的子类，
                // 不排除就会把「模型没在预算内回话」记成「用户自己打断」。
                val cancelled = cause is CancellationException &&
                    cause !is TimeoutCancellationException
                val logError = if (success) {
                    null
                } else if (cancelled) {
                    // 取消原因由 cancel 点带在异常链里（如"被新一轮起播取代"），原样进日志
                    cancellationReason(cause)
                } else {
                    cause?.message ?: cause?.javaClass?.simpleName
                }
                aiLogRepository.record(
                    AiLogEntry(
                        timeMillis = start,
                        kind = "generateStream",
                        providerName = provider.name,
                        providerProtocol = provider.protocol,
                        modelId = model.modelId,
                        modelDisplayName = model.displayName,
                        summary = logConclusion(
                            taskType = request.taskType,
                            cancelled = cancelled,
                            error = logError,
                            outputChars = outputBuilder.length,
                            hasReasoning = reasoningBuilder.isNotEmpty(),
                        ),
                        success = success,
                        cancelled = cancelled,
                        durationMillis = System.currentTimeMillis() - start,
                        error = logError,
                        scenario = listOfNotNull(
                            aiTaskSceneLabel(request.taskType),
                            request.sourceLabel,
                        ).joinToString(" · "),
                        steps = recording.steps,
                    )
                )
            }
    }

    override suspend fun fetchModels(
        provider: AiProviderConfig
    ): Result<List<AiAvailableModel>> {
        val start = System.currentTimeMillis()
        val recording = RecordingTrace(start)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(callTimeoutMs()) {
                    registry.handlerFor(provider.protocol).fetchModels(provider, recording).getOrThrow()
                }
            }
        }
        aiLogRepository.record(
            AiLogEntry(
                timeMillis = start,
                kind = "fetchModels",
                providerName = provider.name,
                providerProtocol = provider.protocol,
                summary = "拉取模型列表",
                success = result.isSuccess,
                durationMillis = System.currentTimeMillis() - start,
                error = result.exceptionOrNull()?.message,
                steps = recording.steps,
            )
        )
        return result
    }

    /**
     * 从取消异常链里取 cancel 点挂上的人话原因。
     *
     * job.cancel(CancellationException("原因")) 之后，正在跑的协程拿到的是
     * "xx was cancelled" 的包装异常，真正的原因在 cause 链上；只读最外层消息
     * 会把"其实标了原因"的取消也显示成没法读的兜底话。
     */
    private fun cancellationReason(e: Throwable?): String? {
        var cursor: Throwable? = e
        while (cursor != null) {
            val message = cursor.message
            if (!message.isNullOrBlank() &&
                !message.contains("was cancelled") &&
                !message.contains("due to cancellation")
            ) {
                return message
            }
            cursor = cursor.cause
        }
        return null
    }

    /**
     * 日志卡片的诊断结论（不放弃技术细节：原始异常仍写 [AiLogEntry.error]，原始
     * 输入输出在展开后可见）。取消按场景翻成人话——"y1 was cancelled" 这类协程
     * 载体名对排查毫无帮助；失败给一句分类与建议；成功给输出统计。
     */
    private fun logConclusion(
        taskType: String?,
        cancelled: Boolean,
        error: String?,
        outputChars: Int,
        hasReasoning: Boolean,
    ): String = when {
        cancelled -> when {
            // 原因由 cancel 点带在异常消息上；没带的只剩协程名（"y1 was cancelled"）
            error.isNullOrBlank() -> "调用被取消：上层未标注原因"
            else -> "调用被取消：$error"
        }
        error != null && error.contains("超时") ->
            "请求超时：可在 AI 设置调大「调用超时」，或减少单次输入长度后重试"
        error != null -> "调用失败：$error"
        else -> buildString {
            append("成功，输出 $outputChars 字")
            if (hasReasoning) append("，含思考内容")
        }
    }
}

/**
 * 输出预算被供应商拒绝后最多降几档。三档够对半砍过 30000→15000→7500→3750，
 * 再多就是在浪费用户的请求配额，直接报原始失败让人换模型。
 */
private const val MAX_OUTPUT_CEILING_FALLBACKS = 3
