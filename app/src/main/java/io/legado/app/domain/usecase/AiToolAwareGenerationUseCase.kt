package io.legado.app.domain.usecase

import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
import io.legado.app.data.repository.ai.formatAiPromptForLog
import io.legado.app.data.repository.ai.truncateForLog
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.gateway.AiToolGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiToolContext
import io.legado.app.domain.model.aiTaskSceneLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AiToolAwareGenerationUseCase(
    private val aiTextGateway: AiTextGateway,
    private val aiToolGateway: AiToolGateway,
    private val aiLogRepository: AiLogRepository,
) {

    suspend fun generate(request: AiGenerateRequest): String {
        val output = StringBuilder()
        generateStream(request).collect { event ->
            if (event is AiStreamEvent.Content) {
                output.append(event.text)
            }
        }
        return output.toString().trim()
            .ifEmpty { error("AI returned empty text") }
    }

    fun generateStream(request: AiGenerateRequest): Flow<AiStreamEvent> = flow {
        val start = System.currentTimeMillis()
        val provider = request.model.provider
        val model = request.model
        var currentRequest = request.let { if (it.readOnlyTools) it.withReadOnlyTools() else it }
        var lastError: String? = null
        var success = false
        // 整个工具循环的思考与输出聚合，供 finally 里写入 AI 日志
        val reasoningBuilder = StringBuilder()
        val outputBuilder = StringBuilder()
        try {
            while (true) {
                val toolTrace = ToolTraceBuilder()
                val roundContent = StringBuilder()
                toolTrace.beginResponse()

                aiTextGateway.generateStream(
                    currentRequest.copy(suppressLog = true)
                ).collect { event ->
                    when (event) {
                        is AiStreamEvent.Content -> {
                            roundContent.append(event.text)
                            outputBuilder.append(event.text)
                            emit(event)
                        }

                        is AiStreamEvent.Reasoning -> {
                            reasoningBuilder.append(event.text)
                            emit(event)
                        }
                        is AiStreamEvent.ToolCallDelta -> {
                            toolTrace.append(event)
                            emit(event)
                        }
                    }
                }

                val toolCalls = toolTrace.pendingToolCalls()
                if (toolCalls.isEmpty()) {
                    success = true
                    return@flow
                }

                val toolResultMessages = toolCalls.map { toolCall ->
                    val result = aiToolGateway.execute(toolCall)
                    AiMessage(
                        role = AiMessageRole.TOOL,
                        content = result.content.truncateToolOutput(),
                        toolCallId = result.callId,
                        name = result.name,
                    )
                }
                currentRequest = currentRequest.copy(
                    messages = currentRequest.messages +
                        AiMessage(
                            role = AiMessageRole.ASSISTANT,
                            content = roundContent.toString(),
                            toolCalls = toolCalls,
                        ) +
                        toolResultMessages,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            lastError = e.message?.takeIf { it.isNotBlank() } ?: "已取消"
            throw e
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            // 重抛而不是吞掉：下游任务需要把 HTTP 429/超时等真实原因透出给用户，
            // 否则只剩"JSON 解析失败"这类指代不清的次生错误
            throw e
        } finally {
            aiLogRepository.record(
                AiLogEntry(
                    timeMillis = start,
                    kind = "generateStream",
                    providerName = provider.name,
                    providerProtocol = provider.protocol,
                    modelId = model.modelId,
                    modelDisplayName = model.displayName,
                    summary = summarizeRequest(request),
                    success = success,
                    durationMillis = System.currentTimeMillis() - start,
                    error = lastError,
                    scenario = aiTaskSceneLabel(request.taskType),
                    // 记录最终一轮的完整请求：包含工具调用历史，才是实际发给模型的内容
                    prompt = formatAiPromptForLog(currentRequest.messages),
                    reasoning = reasoningBuilder.toString().truncateForLog().ifEmpty { null },
                    output = outputBuilder.toString().truncateForLog().ifEmpty { null },
                )
            )
        }
    }

    private fun summarizeRequest(request: AiGenerateRequest): String {
        val content = request.messages
            .lastOrNull { it.role == "user" || it.role == "system" }
            ?.content
            ?: request.messages.lastOrNull()?.content
            .orEmpty()
        return content.replace("\\s+".toRegex(), " ").trim()
    }

    private fun AiGenerateRequest.withReadOnlyTools(): AiGenerateRequest {
        val readOnlyTools = aiToolGateway.availableTools()
            .filterNot { aiToolGateway.requiresConfirmation(it.name) }
        if (readOnlyTools.isEmpty()) return this
        return copy(
            messages = buildList {
                add(TOOL_CONTEXT_MESSAGE)
                toolContext?.toMessage()?.let { add(it) }
                addAll(messages)
            },
            tools = readOnlyTools,
        )
    }

    private fun AiToolContext.toMessage(): AiMessage? {
        val lines = buildList {
            bookUrl?.takeIf { it.isNotBlank() }?.let { add("bookUrl: $it") }
            bookName?.takeIf { it.isNotBlank() }?.let { add("bookName: $it") }
            chapterIndex?.let { add("chapterIndex: $it") }
            chapterTitle?.takeIf { it.isNotBlank() }?.let { add("chapterTitle: $it") }
        }
        if (lines.isEmpty()) return null
        return AiMessage(
            role = AiMessageRole.SYSTEM,
            content = "Current local book context for read-only tools:\n" +
                lines.joinToString("\n") +
                "\nWhen calling book tools, prefer these exact identifiers unless the user explicitly asks for another book.",
        )
    }

    companion object {
        const val CACHE_PROMPT_VERSION = "tool-aware-generation-v1"

        private val TOOL_CONTEXT_MESSAGE = AiMessage(
            role = AiMessageRole.SYSTEM,
            content = "Read-only local book tools are available. Use them when needed to inspect the current book, list chapters, search cached chapter text by character or plot keyword, read cached neighboring chapters for continuity, or look up saved character profiles, relationships, world-book entries, and outlines. Use tools silently; the final response must still follow the original task output format.",
        )
    }
}
