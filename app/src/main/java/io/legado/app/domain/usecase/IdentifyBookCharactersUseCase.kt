package io.legado.app.domain.usecase

import com.google.gson.JsonParser
import io.legado.app.data.entities.AiArtifact
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.AiArtifactGateway
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiToolGateway
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.WebSearchSettingsGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.AiToolContext
import io.legado.app.domain.model.nativeWebSearchSupport
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

class IdentifyBookCharactersUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiToolAwareGenerationUseCase: AiToolAwareGenerationUseCase,
    private val aiArtifactGateway: AiArtifactGateway,
    private val aiTaskManager: AiTaskManager,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val aiToolGateway: AiToolGateway,
    private val aiWebSearchPrefetchUseCase: AiWebSearchPrefetchUseCase,
    private val webSearchSettingsGateway: WebSearchSettingsGateway,
) {
    data class Candidate(
        val name: String,
        val aliases: List<String>,
        val voiceGender: String,
        val voiceAgeBand: String,
        val role: String,
        val personality: String,
        val summary: String,
        val evidence: String,
        val confidence: Float,
    )

    sealed interface Progress {
        data class Reasoning(val text: String) : Progress
        data class ToolCall(val name: String) : Progress
        data class Done(val candidates: List<Candidate>) : Progress
    }

    suspend fun identify(bookUrl: String): List<Candidate> {
        var candidates = emptyList<Candidate>()
        identifyStream(bookUrl).collect { progress ->
            if (progress is Progress.Done) candidates = progress.candidates
        }
        return candidates
    }

    suspend fun loadLatest(bookUrl: String): List<Candidate> {
        val artifact =
            aiArtifactGateway.observeBookArtifacts(bookUrl, AiTaskType.IDENTIFY_CHARACTERS)
                .first()
                .firstOrNull { it.status == AiArtifact.STATUS_SUCCESS && !it.output.isNullOrBlank() }
                ?: return emptyList()
        return decodeCandidates(artifact.output)
    }

    fun observeTask(bookUrl: String) = aiTaskManager.observeBookTask(
        bookUrl = bookUrl,
        taskType = AiTaskType.IDENTIFY_CHARACTERS,
    )

    suspend fun start(
        bookUrl: String,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): String {
        val identifyPreset = aiProfileGateway.getTaskPreset(AiTaskType.IDENTIFY_CHARACTERS)
        val preset = identifyPreset
            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: error("还没有配置可用的 AI 模型，请先到 AI 设置里为人物识别任务选择模型")
        val prompt = identifyPreset?.promptTemplate?.takeIf(String::isNotBlank)
            ?: aiProfileGateway.defaultPrompt(AiTaskType.IDENTIFY_CHARACTERS)
        val now = System.currentTimeMillis()
        val contentHash = MD5Utils.md5Encode(bookUrl)
        val promptHash =
            MD5Utils.md5Encode(prompt + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION)
        val artifact = AiArtifact(
            id = "${contentHash}_${AiTaskType.IDENTIFY_CHARACTERS}_${promptHash}_${preset.model.id}",
            taskType = AiTaskType.IDENTIFY_CHARACTERS,
            bookUrl = bookUrl,
            contentHash = contentHash,
            promptHash = promptHash,
            modelProfileId = preset.model.id,
            createdAt = now,
            updatedAt = now,
        )
        return aiTaskManager.submit(artifact) {
            var candidates = emptyList<Candidate>()
            identifyStream(bookUrl, reasoningLevel).collect { progress ->
                when (progress) {
                    is Progress.Reasoning -> appendReasoning(progress.text)
                    is Progress.ToolCall -> reportToolCall(progress.name)
                    is Progress.Done -> candidates = progress.candidates
                }
            }
            GSON.toJson(candidates)
        }
    }

    fun decodeCandidates(output: String?): List<Candidate> =
        output?.let { GSON.fromJsonArray<Candidate>(it).getOrNull() }.orEmpty()

    fun identifyStream(
        bookUrl: String,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): Flow<Progress> = flow {
        val identifyPreset = aiProfileGateway.getTaskPreset(AiTaskType.IDENTIFY_CHARACTERS)
        val preset = identifyPreset
            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: error("还没有配置可用的 AI 模型，请先到 AI 设置里为人物识别任务选择模型")
        val prompt = identifyPreset?.promptTemplate?.takeIf(String::isNotBlank)
            ?: aiProfileGateway.defaultPrompt(AiTaskType.IDENTIFY_CHARACTERS)
        val response = StringBuilder()
        // 内置联网开关 = 供应商能力检测 × 供应商设置（用户可在供应商编辑页关闭）
        val nativeWebSearch = preset.model.provider.nativeWebSearchSupport().isSupported &&
            webSearchSettingsGateway.currentSettings
                .isNativeWebSearchEnabled(preset.model.provider.id)
        val searchToolAvailable = aiToolGateway.availableTools().any { it.name == SEARCH_WEB_TOOL }
        val bookName = bookKnowledgeGateway.getBookName(bookUrl)
        // 智谱实测：内置联网与 function 工具调用互斥，带工具的主请求里检索永远不执行；
        // 先用一轮不带工具的纯对话预检索（搜索结果按 token 计费，不消耗按次的搜索资源包），
        // 把结果作为上下文注入主请求。已有 search_web 工具（Tavily/智谱检索兜底）时无需预检索。
        val webSearchContext = if (nativeWebSearch && !searchToolAvailable) {
            runCatching {
                preSearchQuery(bookName)?.let { query ->
                    aiWebSearchPrefetchUseCase.prefetch(preset.model, query)
                }
            }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { content ->
                    "Web search results for this book (obtained via the provider's web search):\n" + content
                }
        } else {
            null
        }
        aiToolAwareGenerationUseCase.generateStream(
            AiGenerateRequest(
                model = preset.model,
                messages = buildList {
                    add(AiMessage(AiMessageRole.SYSTEM, prompt))
                    add(
                        AiMessage(
                            AiMessageRole.SYSTEM,
                            webSearchGuidance(nativeWebSearch, searchToolAvailable),
                        )
                    )
                    webSearchContext?.let { add(AiMessage(AiMessageRole.SYSTEM, it)) }
                    add(
                        AiMessage(
                            AiMessageRole.USER,
                            "识别本书的主要人物。优先联网搜索，再用本地工具验证。只返回要求的 JSON。"
                        )
                    )
                },
                params = preset.params.copy(
                    temperature = 0f,
                    reasoningLevel = reasoningLevel.takeUnless { it == AiReasoningLevel.AUTO }
                        ?: preset.params.reasoningLevel,
                    // 供应商自带联网（智谱/通义/Anthropic/Responses 直连）时显式开启，请求里
                    // 才会下发对应内置工具；否则靠 Tavily 的 search_web 工具兜底（配置好后
                    // AiToolRepository 会自动追加到工具列表）。
                    webSearch = nativeWebSearch,
                    // 人物 JSON 动辄数千字符，且思考也占输出预算；预设里偏小的上限会把
                    // 输出截断成非法 JSON（Gson 在字符串半截抛 EOFException）
                    maxOutputTokens = maxOf(
                        preset.params.maxOutputTokens ?: 0,
                        MIN_IDENTIFY_MAX_OUTPUT_TOKENS,
                    ),
                ),
                // bookUrl 可能是不透明编码串，带上书名让模型无需先全书架搜索确认当前书籍
                toolContext = AiToolContext(
                    bookUrl = bookUrl,
                    bookName = bookName,
                ),
            )
        ).collect { event ->
            when (event) {
                is io.legado.app.domain.gateway.AiStreamEvent.Content -> response.append(event.text)
                is io.legado.app.domain.gateway.AiStreamEvent.Reasoning -> emit(
                    Progress.Reasoning(
                        event.text
                    )
                )

                is io.legado.app.domain.gateway.AiStreamEvent.ToolCallDelta -> {
                    event.name?.takeIf(String::isNotBlank)?.let { emit(Progress.ToolCall(it)) }
                }
            }
        }
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd > jsonStart) {
            "AI 没有按要求返回人物列表（可能被内容安全拦截或临时故障），请重试一次"
        }
        val root = try {
            JsonParser.parseString(response.substring(jsonStart, jsonEnd + 1)).asJsonObject
        } catch (e: Exception) {
            // 输出被 max_tokens 截断时 JSON 缺尾，Gson 在字符串半截抛 EOFException
            throw IllegalStateException(
                "AI 的回答写到一半被输出长度上限截断了，人物列表不完整；请重试，" +
                    "若反复出现可在任务预设里调大输出上限，或改用输出更长的模型",
                e,
            )
        }
        val candidates = root.getAsJsonArray("characters").map { element ->
            val item = element.asJsonObject
            Candidate(
                name = item.get("name")?.asString?.trim().orEmpty(),
                aliases = item.getAsJsonArray("aliases")?.map { it.asString.trim() }.orEmpty(),
                voiceGender = item.get("voiceGender")?.asString ?: "unknown",
                voiceAgeBand = item.get("voiceAgeBand")?.asString ?: "unknown",
                role = item.get("role")?.asString.orEmpty(),
                personality = item.get("personality")?.asString.orEmpty(),
                summary = item.get("summary")?.asString.orEmpty(),
                evidence = item.get("evidence")?.asString.orEmpty(),
                confidence = item.get("confidence")?.asFloat?.coerceIn(0f, 1f) ?: 0f,
            )
        }.filter { it.name.isNotBlank() && it.confidence >= MIN_CONFIDENCE }
            .distinctBy { it.name }
        val now = System.currentTimeMillis()
        val contentHash = MD5Utils.md5Encode(bookUrl)
        val promptHash =
            MD5Utils.md5Encode(prompt + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION)
        aiArtifactGateway.upsertArtifact(
            AiArtifact(
                id = "${contentHash}_${AiTaskType.IDENTIFY_CHARACTERS}_${promptHash}_${preset.model.id}",
                taskType = AiTaskType.IDENTIFY_CHARACTERS,
                bookUrl = bookUrl,
                contentHash = contentHash,
                promptHash = promptHash,
                modelProfileId = preset.model.id,
                status = AiArtifact.STATUS_SUCCESS,
                output = GSON.toJson(candidates),
                createdAt = now,
                updatedAt = now,
            )
        )
        emit(Progress.Done(candidates))
    }

    /**
     * 人物识别的预检索问题：必须是疑问式，否则智谱的搜索意图识别不触发搜索。
     */
    private fun preSearchQuery(bookName: String?): String? {
        if (bookName.isNullOrBlank()) return null
        return "《$bookName》这本书的主要人物有哪些？" +
            "请联网搜索书评、百科等，列出主要人物及其身份简介，并标明信息来源。"
    }

    suspend fun save(bookUrl: String, candidates: List<Candidate>) {
        candidates.forEach { candidate ->
            val existing = bookKnowledgeGateway.getCharacterProfile(bookUrl, candidate.name)
            bookKnowledgeGateway.upsertCharacterProfile(
                BookCharacterProfile(
                    id = existing?.id ?: Uuid.random().toString(),
                    bookUrl = bookUrl,
                    name = candidate.name,
                    aliasesJson = GSON.toJson(
                        (GSON.fromJsonArray<String>(existing?.aliasesJson.orEmpty()).getOrNull()
                            .orEmpty()
                                + candidate.aliases)
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct(),
                    ),
                    role = candidate.role,
                    voiceGender = candidate.voiceGender,
                    voiceAgeBand = candidate.voiceAgeBand,
                    personality = candidate.personality,
                    summary = candidate.summary,
                    // 男主/女主自动成为主角；已手动标记过的不因重新识别而丢失
                    isProtagonist = existing?.isProtagonist == true ||
                            candidate.role in BookCharacterProfile.LEAD_ROLES,
                    source = BookCharacterProfile.SOURCE_AI,
                    confidence = candidate.confidence,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.65f

        /** 与 AiToolRepository.TOOL_SEARCH_WEB 保持一致；domain 层不反向依赖 data 层，故本地声明 */
        private const val SEARCH_WEB_TOOL = "search_web"


        /** 输出上限下限：完整人物 JSON + 思考占用的预算，低于此值会被 max_tokens 截断 */
        private const val MIN_IDENTIFY_MAX_OUTPUT_TOKENS = 4096

        /**
         * 按本次请求的真实联网能力生成一条系统说明。只正面描述"有什么可用"，
         * 不提及任何不存在的工具名：
         * - 供应商内置联网（如智谱 web_search 内置工具）是服务端行为，检索自动完成、
         *   结果直接注入上下文，模型无需调用任何函数；但网关/模型是否真正执行检索
         *   无法在本地确认（不支持时静默无结果），措辞用"有结果就用"，不作保证；
         * - Tavily 配置好后 search_web 是真正的 function 工具，可以点名调用；
         * - 都没有时要求直接依据本地工具与自身知识完成，不作解释。
         */
        fun webSearchGuidance(nativeEnabled: Boolean, searchToolAvailable: Boolean): String = when {
            nativeEnabled && searchToolAvailable ->
                "Web search: the provider's native web search is available. Search results may be " +
                    "provided as a separate system message in this conversation — use them directly, " +
                    "no function call is needed. You may also call the \"$SEARCH_WEB_TOOL\" tool for " +
                    "additional searches. If no search results are present, continue with local tools " +
                    "and your own knowledge without mentioning search."

            nativeEnabled ->
                "Web search: the provider's native web search is available. Search results may be " +
                    "provided as a separate system message in this conversation — use them directly, " +
                    "no function call is needed. If no search results are present, continue with local " +
                    "tools and your own knowledge without mentioning search."

            searchToolAvailable ->
                "Web search: call the \"$SEARCH_WEB_TOOL\" tool whenever web results would help."

            else ->
                "Web search: not available in this request; work from local tools and your own " +
                    "knowledge, and move on without mentioning search."
        }
    }
}
