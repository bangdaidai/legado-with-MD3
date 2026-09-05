package io.legado.app.domain.usecase

import io.legado.app.data.entities.AiArtifact
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.AiArtifactGateway
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskType
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 人物速查的 AI 归纳环节：输入确定性检索拿到的原文摘录，输出人物介绍文字。
 * 只负责「讲」，不负责「找」——找由 SearchContentRepository 的早退扫描完成，
 * 因此模型结论背后始终有确定的章节坐标，禁止使用书外知识补事实。
 */
class ExplainBookCharacterUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiToolAwareGenerationUseCase: AiToolAwareGenerationUseCase,
    private val aiArtifactGateway: AiArtifactGateway,
) {

    data class Excerpt(
        val chapterIndex: Int,
        val chapterTitle: String,
        val text: String,
    )

    suspend fun execute(
        book: Book,
        name: String,
        contextText: String,
        excerpts: List<Excerpt>,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val preset = aiProfileGateway.getTaskPreset(AiTaskType.EXPLAIN_SELECTION)
                ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
                ?: error("No AI model configured for character explanation")
            val prompt = preset.promptTemplate.takeIf(String::isNotBlank) ?: DEFAULT_PROMPT

            val material = buildString {
                appendLine("书名：${book.name}")
                book.author.takeIf { it.isNotBlank() }?.let { appendLine("作者：$it") }
                appendLine("查询人物：$name")
                if (contextText.isNotBlank()) {
                    appendLine("用户长按选中时的附近正文：")
                    appendLine(contextText)
                }
                if (excerpts.isNotEmpty()) {
                    appendLine("原文摘录（用于归纳该人物，章节标题已给出）：")
                    excerpts.forEach { excerpt ->
                        appendLine("【第${excerpt.chapterIndex + 1}章《${excerpt.chapterTitle}》】")
                        appendLine(excerpt.text)
                    }
                } else {
                    appendLine("原文摘录：无（全书未检索到该人物名）")
                }
            }
            val contentHash = MD5Utils.md5Encode("${book.bookUrl}_$name\n$material")
            val promptHash = MD5Utils.md5Encode(prompt + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION)
            aiArtifactGateway.getCachedArtifact(
                bookUrl = book.bookUrl,
                chapterIndex = null,
                taskType = AiTaskType.EXPLAIN_SELECTION,
                contentHash = contentHash,
                promptHash = promptHash,
                modelProfileId = preset.model.id,
            )?.output?.let { return@runCatching it }

            val output = aiToolAwareGenerationUseCase.generate(
                AiGenerateRequest(
                    model = preset.model,
                    messages = listOf(
                        AiMessage(AiMessageRole.SYSTEM, prompt),
                        AiMessage(AiMessageRole.USER, material),
                    ),
                    params = preset.params.copy(
                        temperature = 0f,
                        reasoningLevel = reasoningLevel.takeUnless { it == AiReasoningLevel.AUTO }
                            ?: preset.params.reasoningLevel,
                    ),
                    // 材料已齐（检索摘录全部随请求给出），禁用只读工具注入：
                    // 否则模型会多轮翻书，白烧请求还容易撞免费模型限流（HTTP 429）
                    readOnlyTools = false,
                    taskType = AiTaskType.EXPLAIN_SELECTION,
                )
            ).trim()
            require(output.isNotEmpty()) { "AI returned an empty character explanation" }

            val now = System.currentTimeMillis()
            aiArtifactGateway.upsertArtifact(
                AiArtifact(
                    id = "${book.bookUrl}_character_${contentHash}_${promptHash}_${preset.model.id}",
                    taskType = AiTaskType.EXPLAIN_SELECTION,
                    bookUrl = book.bookUrl,
                    contentHash = contentHash,
                    promptHash = promptHash,
                    modelProfileId = preset.model.id,
                    status = AiArtifact.STATUS_SUCCESS,
                    output = output,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            output
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    companion object {
        /**
         * 恒定硬约束与可编辑预设并存：预设（EXPLAIN_SELECTION，缺省回退 CHAT）可被用户改写，
         * 但「只依据摘录」这条底线写在默认提示词里；用户改坏预设时质量自负，不阻塞功能。
         */
        const val DEFAULT_PROMPT =
            "你是小说阅读助手。用户在阅读时忘记了某个书中人物，请只依据给出的原文摘录和附近正文归纳这个人物是谁。" +
                "禁止使用书外知识补充事实：网络同名人物、你的训练记忆都不可作为依据；摘录中没有的信息不要编造。" +
                "如果摘录不足以确定身份，直说证据不足。输出一段 150 字以内的连续中文，不要 Markdown、不要列表、" +
                "不要标题，内容依次覆盖：身份与与主角的关系、关键经历一两句、当前（最近摘录处）在做什么。"
    }
}
