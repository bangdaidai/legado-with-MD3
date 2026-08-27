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
import io.legado.app.domain.model.aiTaskSceneLabel
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.RecordingTrace
import io.legado.app.help.config.AppConfigStore
import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
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
        val summary = summarizeRequest(request)
        val recording = RecordingTrace(start)

        var response: AiGenerateResponse? = null
        var error: String? = null
        var cancellation: CancellationException? = null
        try {
            response = withContext(Dispatchers.IO) {
                withTimeout(callTimeoutMs()) {
                    registry.handlerFor(provider.protocol).generate(request, recording).getOrThrow()
                }
            }
        } catch (e: TimeoutCancellationException) {
            error = "请求超时"
        } catch (e: CancellationException) {
            cancellation = e
            error = e.message?.takeIf { it.isNotBlank() } ?: "已取消"
        } catch (e: Throwable) {
            error = e.message ?: e.javaClass.simpleName
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
                    summary = summary,
                    success = cancellation == null && error == null,
                    durationMillis = System.currentTimeMillis() - start,
                    error = error,
                    scenario = aiTaskSceneLabel(request.taskType),
                    steps = recording.steps,
                )
            )
        }

        if (cancellation != null) throw cancellation
        return response?.let { Result.success(it) }
            ?: Result.failure(RuntimeException(error ?: "AI 生成失败"))
    }

    override fun generateStream(
        request: AiGenerateRequest
    ): Flow<AiStreamEvent> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val summary = summarizeRequest(request)
        val recording = RecordingTrace(start)
        return flow {
            registry.handlerFor(provider.protocol).stream(request, { emit(it) }, recording)
        }.flowOn(Dispatchers.IO)
            .onCompletion { cause ->
                val success = cause == null
                val cancelled = cause is CancellationException
                val logError = if (success) {
                    null
                } else if (cancelled) {
                    cause?.message?.takeIf { it.isNotBlank() } ?: "已取消"
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
                        summary = summary,
                        success = success,
                        durationMillis = System.currentTimeMillis() - start,
                        error = logError,
                        scenario = aiTaskSceneLabel(request.taskType),
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

    private fun summarizeRequest(request: AiGenerateRequest): String {
        val content = request.messages
            .lastOrNull { it.role == "user" || it.role == "system" }
            ?.content
            ?: request.messages.lastOrNull()?.content
            .orEmpty()
        return content.replace("\\s+".toRegex(), " ").trim()
    }
}
