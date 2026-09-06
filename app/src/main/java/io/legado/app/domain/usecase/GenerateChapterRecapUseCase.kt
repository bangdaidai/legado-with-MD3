package io.legado.app.domain.usecase

import io.legado.app.data.entities.AiArtifact
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.AiArtifactGateway
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskPresetConfig
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.AiToolContext
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 前文回顾：基于当前章之前的若干章正文，生成一段连贯的"前情回顾"。
 *
 * 与 [GenerateChapterSummaryUseCase] 的单章梗概不同，这里不逐章先摘要再合并，
 * 而是把前几章正文（超出上限时从最早的章节开始截断，保留离当前章最近的内容）
 * 一次性交给模型。缓存以"当前章 index + 拼接输入哈希"为键，往前读一章即自动失效。
 */
class GenerateChapterRecapUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiToolAwareGenerationUseCase: AiToolAwareGenerationUseCase,
    private val aiArtifactGateway: AiArtifactGateway,
    private val aiTaskManager: AiTaskManager,
) {

    sealed interface StreamEvent {
        data class Content(val text: String) : StreamEvent
        data class Reasoning(val text: String) : StreamEvent
        data class Done(val text: String, val reasoning: String) : StreamEvent
    }

    fun observeTask(bookUrl: String, chapterIndex: Int) = aiTaskManager.observeBookTask(
        bookUrl = bookUrl,
        taskType = AiTaskType.RECAP_RECENT,
        chapterIndex = chapterIndex,
    )

    suspend fun start(
        book: Book,
        currentChapter: BookChapter,
        previousChapters: List<BookChapter>,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): String {
        val input = buildRecapInput(book, previousChapters)
        if (input.isBlank()) error("No previous chapter content available")
        val preset = resolvePreset() ?: error("No AI model configured for chapter recap")
        val contentHash = MD5Utils.md5Encode(input)
        val promptHash = MD5Utils.md5Encode(
            preset.promptTemplate + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION,
        )
        val now = System.currentTimeMillis()
        val artifact = AiArtifact(
            id = "${book.bookUrl}_${currentChapter.index}_${AiTaskType.RECAP_RECENT}_${contentHash}_${preset.model.id}",
            taskType = AiTaskType.RECAP_RECENT,
            bookUrl = book.bookUrl,
            chapterIndex = currentChapter.index,
            contentHash = contentHash,
            promptHash = promptHash,
            modelProfileId = preset.model.id,
            createdAt = now,
            updatedAt = now,
        )
        return aiTaskManager.submit(artifact) {
            var recap = ""
            executeStream(
                book = book,
                currentChapter = currentChapter,
                previousChapters = previousChapters,
                reasoningLevel = reasoningLevel,
            ).collect { event ->
                when (event) {
                    is StreamEvent.Content -> Unit
                    is StreamEvent.Reasoning -> Unit
                    is StreamEvent.Done -> recap = event.text
                }
            }
            recap.ifBlank { error("AI returned an empty chapter recap") }
        }
    }

    fun executeStream(
        book: Book,
        currentChapter: BookChapter,
        previousChapters: List<BookChapter>,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): Flow<StreamEvent> = flow {
        val input = buildRecapInput(book, previousChapters)
        if (input.isBlank()) error("No previous chapter content available")
        val preset = resolvePreset() ?: error("No AI model configured for chapter recap")
        val contentHash = MD5Utils.md5Encode(input)
        val promptHash = MD5Utils.md5Encode(
            preset.promptTemplate + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION
        )
        aiArtifactGateway.getCachedArtifact(
            bookUrl = book.bookUrl,
            chapterIndex = currentChapter.index,
            taskType = AiTaskType.RECAP_RECENT,
            contentHash = contentHash,
            promptHash = promptHash,
            modelProfileId = preset.model.id
        )?.output?.let { cached ->
            emit(StreamEvent.Content(cached))
            emit(StreamEvent.Done(cached, ""))
            return@flow
        }

        val outputBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val userContent = buildString {
            append("Current chapter title: ").append(currentChapter.title).append('\n')
            append("\nPrevious chapters:\n\n")
            append(input)
        }
        aiToolAwareGenerationUseCase.generateStream(
            AiGenerateRequest(
                model = preset.model,
                messages = listOf(
                    AiMessage(AiMessageRole.SYSTEM, preset.promptTemplate),
                    AiMessage(AiMessageRole.USER, userContent)
                ),
                params = preset.params.copy(
                    reasoningLevel = reasoningLevel.takeUnless { it == AiReasoningLevel.AUTO }
                        ?: preset.params.reasoningLevel,
                ),
                toolContext = book.toToolContext(currentChapter),
                taskType = AiTaskType.RECAP_RECENT,
            )
        ).collect { event ->
            when (event) {
                is AiStreamEvent.Content -> {
                    outputBuilder.append(event.text)
                    emit(StreamEvent.Content(event.text))
                }

                is AiStreamEvent.Reasoning -> {
                    reasoningBuilder.append(event.text)
                    emit(StreamEvent.Reasoning(event.text))
                }

                is AiStreamEvent.ToolCallDelta -> Unit
            }
        }

        val recap = outputBuilder.toString().trim()
            .ifEmpty { error("AI returned an empty chapter recap") }
        val reasoning = reasoningBuilder.toString()
        val now = System.currentTimeMillis()
        aiArtifactGateway.upsertArtifact(
            AiArtifact(
                id = "${book.bookUrl}_${currentChapter.index}_${AiTaskType.RECAP_RECENT}_${contentHash}_${preset.model.id}",
                taskType = AiTaskType.RECAP_RECENT,
                bookUrl = book.bookUrl,
                chapterIndex = currentChapter.index,
                contentHash = contentHash,
                promptHash = promptHash,
                modelProfileId = preset.model.id,
                status = AiArtifact.STATUS_SUCCESS,
                output = recap,
                createdAt = now,
                updatedAt = now
            )
        )
        emit(StreamEvent.Done(recap, reasoning))
    }.flowOn(Dispatchers.IO)

    /**
     * 按章节顺序拼接前文正文；总长超出 [DEFAULT_MAX_RECAP_CHARS] 时，
     * 从最早的章节开始丢弃/截断，保留离当前章最近的内容。
     */
    private fun buildRecapInput(book: Book, previousChapters: List<BookChapter>): String {
        val ordered = previousChapters.sortedBy { it.index }
        val sections = mutableListOf<String>()
        var total = 0
        for (chapter in ordered.asReversed()) {
            val content = BookHelp.getContent(book, chapter) ?: continue
            if (content.isBlank()) continue
            val remaining = DEFAULT_MAX_RECAP_CHARS - total
            if (remaining <= 0) break
            val text = if (content.length > remaining) {
                content.substring(content.length - remaining)
            } else {
                content
            }
            total += text.length
            sections += "Chapter title: ${chapter.title}\n\nText:\n$text"
        }
        return sections.asReversed().joinToString("\n\n")
    }

    private suspend fun resolvePreset(): AiTaskPresetConfig? {
        return aiProfileGateway.getTaskPreset(AiTaskType.RECAP_RECENT)
    }

    private fun Book.toToolContext(bookChapter: BookChapter): AiToolContext {
        return AiToolContext(
            bookUrl = bookUrl,
            bookName = name,
            chapterIndex = bookChapter.index,
            chapterTitle = bookChapter.title,
        )
    }

    private companion object {
        const val DEFAULT_MAX_RECAP_CHARS = 30000
    }
}
