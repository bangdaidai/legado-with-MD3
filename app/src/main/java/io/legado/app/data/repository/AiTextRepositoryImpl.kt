package io.legado.app.data.repository

import io.legado.app.data.repository.ai.AiProviderRegistry
import io.legado.app.data.repository.ai.AnthropicHandler
import io.legado.app.data.repository.ai.OpenAiChatHandler
import io.legado.app.data.repository.ai.OpenAiResponsesHandler
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.model.AiAvailableModel
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerateResponse
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AiTextRepositoryImpl(
    private val aiLogRepository: AiLogRepository,
) : AiTextGateway {

    private val AI_CALL_TIMEOUT_MS = 120_000L

    private val registry = AiProviderRegistry(
        handlers = listOf(
            OpenAiChatHandler(),
            OpenAiResponsesHandler(),
            AnthropicHandler()
        )
    )

    override suspend fun generate(request: AiGenerateRequest): Result<AiGenerateResponse> {
        val start = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(AI_CALL_TIMEOUT_MS) {
                    registry.handlerFor(request.model.provider.protocol).generate(request).getOrThrow()
                }
            }
        }
        aiLogRepository.record(
            AiLogEntry(
                timeMillis = start,
                kind = "generate",
                providerName = request.model.provider.name,
                providerProtocol = request.model.provider.protocol,
                modelId = request.model.modelId,
                modelDisplayName = request.model.displayName,
                summary = summarizeRequest(request),
                success = result.isSuccess,
                durationMillis = System.currentTimeMillis() - start,
                error = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName,
            )
        )
        return result
    }

    override fun generateStream(request: AiGenerateRequest): Flow<AiStreamEvent> {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        val summary = summarizeRequest(request)
        return flow {
            registry.handlerFor(provider.protocol).stream(request) { emit(it) }
        }.flowOn(Dispatchers.IO)
            .onCompletion { cause ->
                val success = cause == null
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
                        error = if (success) null else (cause?.message ?: cause?.javaClass?.simpleName),
                    )
                )
            }
    }

    override suspend fun fetchModels(provider: AiProviderConfig): Result<List<AiAvailableModel>> {
        val start = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(AI_CALL_TIMEOUT_MS) {
                    registry.handlerFor(provider.protocol).fetchModels(provider).getOrThrow()
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
        return content.replace("\\s+".toRegex(), " ").take(160)
    }
}
