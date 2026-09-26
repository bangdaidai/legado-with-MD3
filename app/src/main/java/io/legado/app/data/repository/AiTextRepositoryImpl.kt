package io.legado.app.data.repository

import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.ai.AiProviderRegistry
import io.legado.app.data.repository.ai.AnthropicHandler
import io.legado.app.data.repository.ai.OpenAiChatHandler
import io.legado.app.data.repository.ai.OpenAiResponsesHandler
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.model.AiAvailableModel
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
        val seconds = AppConfigStore.getInt(PreferKey.aiCallTimeout) ?: 60
        return seconds.coerceIn(5, 600) * 1000L
    }

    override suspend fun generate(
        request: AiGenerateRequest
    ): Result<AiGenerateResponse> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val promptForLog = formatAiPromptForLog(request.messages)
        val recording = RecordingTrace(start)

        var response: AiGenerateResponse? = null
        var error: String? = null
        // 保留原始异常供 Result.failure 返回。之前这里统一包成 RuntimeException，
        // AiHttpException(429) 等类型信息全部丢失，下游 aiFailureKind() 只能按 message
        // 猜，限流/超时分类全部失效。
        var failure: Throwable? = null
        var cancellation: CancellationException? = null
        try {
            response = withContext(Dispatchers.IO) {
                withTimeout(callTimeoutMs()) {
                    registry.handlerFor(provider.protocol).generate(request, recording).getOrThrow()
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
                        error = error,
                        outputChars = response?.text?.length ?: 0,
                        hasReasoning = !response?.reasoning.isNullOrBlank(),
                    ),
                    success = cancellation == null && error == null,
                    // 取消单独一档：超时在上面已经按「请求超时」归到失败，不会走到这里
                    cancelled = cancellation != null,
                    durationMillis = System.currentTimeMillis() - start,
                    error = error,
                    scenario = aiTaskSceneLabel(request.taskType),
                    steps = recording.steps,
                    prompt = promptForLog,
                    reasoning = response?.reasoning?.truncateForLog()?.ifEmpty { null },
                    output = response?.text?.truncateForLog()?.ifEmpty { null },
                )
            )
        }

        if (cancellation != null) throw cancellation
        return response?.let { Result.success(it) }
            ?: Result.failure(failure ?: RuntimeException(error ?: "AI 生成失败"))
    }

    override fun generateStream(
        request: AiGenerateRequest
    ): Flow<AiStreamEvent> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val recording = RecordingTrace(start)
        val suppressLog = request.suppressLog
        // suppressLog 的调用方（工具感知生成）会自己合并落一条日志，这里不再格式化提示词
        val promptForLog = if (suppressLog) null else formatAiPromptForLog(request.messages)
        // 事件在发给上层的同时留一份聚合，调用结束后写进 AI 日志
        val reasoningBuilder = StringBuilder()
        val outputBuilder = StringBuilder()
        return flow {
            registry.handlerFor(provider.protocol).stream(request, { event ->
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
                    null
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
                        scenario = aiTaskSceneLabel(request.taskType),
                        steps = recording.steps,
                        prompt = promptForLog,
                        reasoning = reasoningBuilder.toString().truncateForLog().ifEmpty { null },
                        output = outputBuilder.toString().truncateForLog().ifEmpty { null },
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
        cancelled -> when (taskType) {
            AiTaskType.ANALYZE_SPEECH ->
                "朗读轮次切换或停止朗读时被取消，属正常轮替；本次分析未完成，下次分析会重跑"
            else -> "调用被上层取消（切换会话/翻页/停止），非模型或网络故障"
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
