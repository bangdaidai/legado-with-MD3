package io.legado.app.ui.book.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.usecase.ExplainBookCharacterUseCase
import io.legado.app.data.repository.SearchContentRepository
import io.legado.app.domain.usecase.GetChapterContentUseCase
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.uuid.Uuid

/**
 * 人物速查三级漏斗：
 * 1. 本地 [BookCharacterProfile] 档案命中 → 直接出卡，零 token；
 * 2. [SearchContentRepository] 早退扫描已缓存正文 → 拿到「最初登场 / 最近出场」确定性锚点；
 * 3. 档案未命中时才让 [ExplainBookCharacterUseCase] 依据摘录归纳介绍，可保存回档案表。
 */
class CharacterQueryViewModel(
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val searchContentRepository: SearchContentRepository,
    private val explainBookCharacterUseCase: ExplainBookCharacterUseCase,
    private val getChapterContentUseCase: GetChapterContentUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CharacterQueryUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CharacterQueryEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var queryJob: Job? = null
    private var saveJob: Job? = null

    fun onIntent(intent: CharacterQueryIntent) {
        when (intent) {
            is CharacterQueryIntent.Load -> load(intent.name)
            is CharacterQueryIntent.Retry -> retry()
            is CharacterQueryIntent.JumpTo -> {
                _effects.tryEmit(CharacterQueryEffect.JumpToChapter(intent.chapterIndex))
            }
            is CharacterQueryIntent.SaveProfile -> saveProfile()
            is CharacterQueryIntent.TraceFromStart -> traceFromStart()
        }
    }

    /**
     * 用户主动触发的「从头追查」：顺序补下载缺失章节（上限 50 章）并逐章检索，
     * 找到更早登场即更新锚点；下载成功的章节写入缓存，之后普通全文搜索也能搜到。
     */
    private var traceJob: Job? = null

    private fun traceFromStart() {
        if (_uiState.value.tracing) return
        val book = ReadBook.book ?: return
        val name = _uiState.value.name
        traceJob?.cancel()
        _uiState.update { it.copy(tracing = true, traceProgress = null) }
        traceJob = viewModelScope.launch {
            runCatching {
                searchContentRepository.findFirstWithDownload(
                    book = book,
                    query = name,
                    downloadChapter = { chapter, nextChapterUrl ->
                        val content = getChapterContentUseCase.getContent(
                            book = book,
                            chapter = chapter,
                            nextChapterUrl = nextChapterUrl,
                        )
                        BookHelp.saveText(book, chapter, content)
                    },
                    onProgress = { scanned, total, downloaded ->
                        _uiState.update {
                            it.copy(
                                traceProgress = CharacterQueryUiState.TraceProgress(
                                    scanned = scanned,
                                    total = total,
                                    downloaded = downloaded,
                                )
                            )
                        }
                    },
                )
            }.fold(
                onSuccess = { result ->
                    _uiState.update { state ->
                        state.copy(
                            tracing = false,
                            traceProgress = null,
                            traceFinished = true,
                            firstAppearance = result?.toAppearance() ?: state.firstAppearance,
                        )
                    }
                    // 追查到更早原文后，重新归纳一次（缓存键变了会真实重算）
                    if (result != null && _uiState.value.profile == null) {
                        explainWithAi(book.bookUrl, name)
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(tracing = false, traceProgress = null, error = error.message ?: error.toString())
                    }
                },
            )
        }
    }

    private fun retry() {
        val name = _uiState.value.name
        if (name.isNotBlank()) load(name)
    }

    private fun load(name: String) {
        queryJob?.cancel()
            _uiState.update {
                it.copy(
                    name = name,
                    isLoading = true,
                    error = null,
                    saveState = CharacterQueryUiState.SaveState.Idle,
                    tracing = false,
                    traceProgress = null,
                    traceFinished = false,
                )
            }
        queryJob = viewModelScope.launch {
            val book = ReadBook.book
            if (book == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "书籍未加载")
                }
                return@launch
            }
            runCatching {
                // 第一级：本地档案（先精确 name，再归一化兜底：中间点等符号变体在正文与档案里常不一致）
                val profile = bookKnowledgeGateway.getCharacterProfile(book.bookUrl, name)
                    ?: matchProfile(book.bookUrl, name)

                // 第二级：确定性全文检索锚点（从头找最初登场，从尾找最近出场）
                val firstAppearance = withContext(Dispatchers.IO) {
                    searchContentRepository.findFirstFromStart(book, name)
                }
                val latestAppearance = withContext(Dispatchers.IO) {
                    searchContentRepository.findLastFromEnd(book, name)
                }
                val fullyCovered = withContext(Dispatchers.IO) {
                    searchContentRepository.cachedChapterRatio(book) >= 0.999f
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profile = profile,
                        firstAppearance = firstAppearance?.toAppearance(),
                        latestAppearance = latestAppearance?.toAppearance(),
                        searchFullyCovered = fullyCovered,
                    )
                }

                // 第三级：档案未命中才调 AI 归纳
                if (profile == null) {
                    explainWithAi(book.bookUrl, name)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: error.toString())
                }
            }
        }
    }

    /** 精确名未命中时做归一化匹配：正文与档案里中间点等符号常不一致（·／・／•），先统一再比对。 */
    private suspend fun matchProfile(bookUrl: String, name: String): BookCharacterProfile? {
        val profiles = bookKnowledgeGateway.getCharacterProfiles(bookUrl, limit = 500)
        val target = normalizeName(name)
        if (target.isEmpty()) return null
        profiles.firstOrNull { normalizeName(it.name) == target }?.let { return it }
        return profiles.firstOrNull { profile ->
            candidateNames(profile).any { alias ->
                val normalized = normalizeName(alias)
                // 包含式匹配要求两边至少 2 字符，避免「爷」「叔」这类称呼误命中
                normalized.length >= 2 &&
                    (normalized.contains(target) || target.contains(normalized))
            }
        }
    }

    private fun candidateNames(profile: BookCharacterProfile): List<String> =
        listOf(profile.name) +
            GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty()

    private fun normalizeName(name: String): String {
        val normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
            .replace(MIDDLE_DOT_REGEX, MIDDLE_DOT_CANONICAL)
            .replace(CJK_ADJACENT_DOT_REGEX, MIDDLE_DOT_CANONICAL)
        return normalized.filterNot { it.isWhitespace() }.lowercase()
    }

    private suspend fun explainWithAi(bookUrl: String, name: String) {
        _uiState.update { it.copy(aiLoading = true, aiSummary = "", error = null) }
        val book = ReadBook.book ?: return
        val excerpts = buildList {
            _uiState.value.firstAppearance?.let {
                add(ExplainBookCharacterUseCase.Excerpt(it.chapterIndex, it.chapterTitle, it.excerpt))
            }
            _uiState.value.latestAppearance?.let {
                if (it.chapterIndex != _uiState.value.firstAppearance?.chapterIndex) {
                    add(ExplainBookCharacterUseCase.Excerpt(it.chapterIndex, it.chapterTitle, it.excerpt))
                }
            }
        }
        val result = explainBookCharacterUseCase.execute(
            book = book,
            name = name,
            contextText = "",
            excerpts = excerpts,
        )
        _uiState.update { state ->
            if (state.name != name) return@update state
            result.fold(
                onSuccess = { text ->
                    state.copy(aiLoading = false, aiSummary = text)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    state.copy(aiLoading = false, error = error.message ?: error.toString())
                },
            )
        }
    }

    private fun saveProfile() {
        val state = _uiState.value
        val book = ReadBook.book ?: return
        if (state.saveState == CharacterQueryUiState.SaveState.Saving) return
        saveJob?.cancel()
        _uiState.update { it.copy(saveState = CharacterQueryUiState.SaveState.Saving) }
        saveJob = viewModelScope.launch {
            runCatching {
                // 归一化匹配已有档案（中间点等符号变体算同一人），有则更新而非新增
                val existing = bookKnowledgeGateway.getCharacterProfile(book.bookUrl, state.name)
                    ?: matchProfile(book.bookUrl, state.name)
                val saved = BookCharacterProfile(
                    id = existing?.id ?: Uuid.random().toString(),
                    bookUrl = book.bookUrl,
                    name = existing?.name ?: state.name,
                    aliasesJson = existing?.aliasesJson ?: "[]",
                    summary = state.aiSummary.ifBlank { existing?.summary.orEmpty() },
                    source = BookCharacterProfile.SOURCE_AI,
                    confidence = existing?.confidence ?: 0.8f,
                    status = BookCharacterProfile.STATUS_ACTIVE,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                bookKnowledgeGateway.upsertCharacterProfile(saved)
                saved
            }.fold(
                onSuccess = { saved ->
                    _uiState.update { it.copy(saveState = CharacterQueryUiState.SaveState.Saved, profile = saved) }
                    _effects.tryEmit(CharacterQueryEffect.ShowToast(SAVE_SUCCESS))
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(saveState = CharacterQueryUiState.SaveState.Failed) }
                    _effects.tryEmit(
                        CharacterQueryEffect.ShowToast(error.message ?: error.toString())
                    )
                },
            )
        }
    }

    private fun io.legado.app.ui.book.searchContent.SearchResult.toAppearance() =
        CharacterQueryUiState.CharacterAppearance(
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            // 原文截取常带换行/缩进，压成单行空格分隔，避免卡内排版错乱
            excerpt = resultText.replace(Regex("\\s+"), " ").trim(),
        )

    companion object {
        const val SAVE_SUCCESS = "已保存到人物档案"

        /** 中间点类分隔符的全部常见变体：拉丁中点、全角点、片假名中点、项目符号等 */
        private val MIDDLE_DOT_REGEX =
            Regex("[\u00B7\u2022\u2027\u2219\u22C5\u30FB\uFF65\uFF0E\u2024]")

        /**
         * ASCII 半角句点单独处理：仅在紧邻汉字/假名时才视为人名分隔点（如「奥黛丽.赫本」），
         * 避免「3.14」「J.K.」这类非人名用法被误替换。
         */
        private val CJK_ADJACENT_DOT_REGEX =
            Regex("(?<=[\u4E00-\u9FFF\u3040-\u30FF])\\.|\\.(?=[\u4E00-\u9FFF])")

        private const val MIDDLE_DOT_CANONICAL = "·"
    }
}

sealed interface CharacterQueryEffect {
    data class JumpToChapter(val chapterIndex: Int) : CharacterQueryEffect
    data class ShowToast(val message: String) : CharacterQueryEffect
}
