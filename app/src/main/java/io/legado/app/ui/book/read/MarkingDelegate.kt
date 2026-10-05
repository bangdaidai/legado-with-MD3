package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.HighlightRuleRepository
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.usecase.SaveMarkingUseCase
import io.legado.app.feature.reader.core.selection.ReaderSelectionMenuAnchor
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.ReaderMarkingNoteState
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 划线/高亮笔记域：承载一次「选中 → 配置样式/备注 → 保存」的会话。
 *
 * 落库走 [SaveMarkingUseCase]（book_marks 表），与书签、AI 正文处理完全独立。
 * 再次选中同一段文字划线时，按锚点查到已有标记进入编辑模式（预填样式与备注）。
 *
 * 本域同时持有**备注快照**（[ReaderMarkingNoteState]）：渲染层要在主线程为每页判定
 * 「这条划线要不要画备注角标」，不能起协程查库，因此整本书的备注在这里订阅一次写进快照。
 */
class MarkingDelegate(
    private val scope: CoroutineScope,
    private val context: Context,
    private val highlightRuleRepository: HighlightRuleRepository,
    private val saveMarkingUseCase: SaveMarkingUseCase,
    private val bookMarkingGateway: BookMarkingGateway,
    /** 当前书的 `书名 to 作者`（未开书时为 null）——book_marks 认这两个字段做跨源关联。 */
    private val bookKey: Flow<Pair<String, String>?>,
    private val host: Host,
) {
    interface Host {
        fun reloadCurrentChapter()
        fun dismissMarkingSheet()
        /** 一键保存遇到已有标记时转编辑：请求宿主打开笔记 Sheet（此时选区已就绪）。 */
        fun openMarkingSheet()
        /**
         * 取消排版层选区。笔记会话期间选区是样式实时预览的绘制载体，
         * 所以只在预览不再需要它的时候调用：纯关闭时立刻，保存后等重排批次提交。
         */
        fun cancelReaderSelection()
        fun showToast(message: String)
        fun emitEffect(effect: ReadBookEffect)
    }

    private val _uiState = MutableStateFlow(MarkingUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * 正文里的只读备注浮窗（点笔记角标打开）。与 [uiState] 分开是因为两者生命周期不同：
     * 弹层会话结束即重置 [uiState]，而浮窗与弹层互不干涉，必须能独立存在。
     */
    private val _notePreview = MutableStateFlow<MarkingNotePreview?>(null)
    val notePreview = _notePreview.asStateFlow()

    /** 保存后等待重排批次提交的章节；提交前预览必须盖住旧页，否则关闭弹层会闪回旧样式。 */
    private var previewCommitPending: Int? = null

    /**
     * 维护 [ReaderMarkingNoteState]：正文里的备注角标由渲染层在主线程同步算，不能起协程
     * 查库，所以把当前书的备注整份缓存进会话快照。VM 构造时调一次。
     *
     * 收集随 [scope] 结束，`finally` 里清掉快照——否则下一本书会读到上一本的备注，
     * 在新书上凭空画出角标。每次快照变化都发一次 [ReadBookEffect.UpMarkingNoteBadges]
     * 让宿主重发取页窗口，角标才会跟着保存/删除/改备注实时增减。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            try {
                bookKey
                    .distinctUntilChanged()
                    .flatMapLatest { key ->
                        if (key == null) {
                            flowOf(key to emptyList())
                        } else {
                            bookMarkingGateway.flowByBook(key.first, key.second).map { key to it }
                        }
                    }
                    .collect { (key, markings) ->
                        if (key == null) {
                            // 没有书就没有角标可言，也就没有重发窗口的必要。
                            ReaderMarkingNoteState.clear()
                        } else {
                            ReaderMarkingNoteState.update(key.first, key.second, markings)
                            host.emitEffect(ReadBookEffect.UpMarkingNoteBadges)
                        }
                    }
            } finally {
                ReaderMarkingNoteState.clear()
            }
        }
    }

    /**
     * 点笔记角标：只摊开备注给人看。
     *
     * 备注原文从 [ReaderMarkingNoteState] 取（角标就是靠它画出来的，所以此刻必然有值）；
     * 取不到说明快照已被换书清掉，此时静默不弹——凭空造一条空浮窗比不弹更糟。
     * 不建选区、不进笔记弹层、不落库：编辑仍走点原文。
     */
    fun showNote(markingId: String, anchor: ReaderSelectionMenuAnchor) {
        val note = ReaderMarkingNoteState.noteFor(markingId)?.takeIf { it.isNotBlank() } ?: return
        _notePreview.value = MarkingNotePreview(
            markingId = markingId,
            note = note,
            anchor = anchor,
        )
    }

    /** 关掉备注浮窗（点外部/返回键，以及翻页后锚点失效时由宿主主动关）。 */
    fun dismissNote() {
        _notePreview.value = null
    }

    /**
     * 点「笔记」一键保存：新段落直接套用 [DefaultMarkingStyle] 落库、不弹样式选择，
     * 也不必每次单独挑样式。若该选区同锚点已有标记，则不静默覆盖（会清空备注），
     * 而是预填样式与备注后请求宿主打开编辑 Sheet，交回用户修改。
     */
    fun saveQuick(selection: Bookmark) {
        val book = ReadBook.book ?: return
        dismissNote()
        previewCommitPending = null
        _uiState.update {
            it.copy(
                selection = selection,
                editing = null,
                highlightRules = persistentListOf(),
                loading = true,
                previewStyle = null,
                floatingAnchor = null,
            )
        }
        scope.launch(IO) {
            val existing = runCatching {
                saveMarkingUseCase.find(
                    bookName = book.name,
                    bookAuthor = book.author,
                    chapterIndex = selection.chapterIndex,
                    chapterPosition = selection.chapterPos,
                    selectedText = selection.bookText,
                )
            }.getOrNull()
            if (existing != null) {
                val rules = runCatching {
                    highlightRuleRepository.load(ReadBookConfig.durConfig.name)
                }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        highlightRules = rules.toImmutableList(),
                        editing = existing,
                        loading = false,
                    )
                }
                host.openMarkingSheet()
                return@launch
            }
            val style = DefaultMarkingStyle.get()
            // 先把默认样式钉成预览，持久化 + 重排 + 批次提交完成前保持，避免闪回无划线旧页。
            _uiState.update { it.copy(previewStyle = style) }
            previewCommitPending = selection.chapterIndex
            runCatching {
                persistMarking(MarkingUiState(selection = selection), book, style, "")
            }.onSuccess {
                host.reloadCurrentChapter()
            }.onFailure { error ->
                previewCommitPending = null
                _uiState.update { it.copy(previewStyle = null) }
                host.showToast(error.localizedMessage ?: context.getString(R.string.error))
            }
        }
    }

    /** 点正文划线或从目录 Sheet 点标记项进入编辑模式：按 id 取完整标记预填。 */
    fun openForEdit(markingId: String) {
        val book = ReadBook.book
        // 进编辑会话即收起只读浮窗：同一页上同时挂着「看备注」和「改备注」两层浮层没意义。
        dismissNote()
        previewCommitPending = null
        _uiState.update {
            it.copy(
                selection = null,
                editing = null,
                highlightRules = persistentListOf(),
                loading = true,
                previewStyle = null,
                floatingAnchor = null,
            )
        }
        scope.launch(IO) {
            val rules = runCatching {
                highlightRuleRepository.load(ReadBookConfig.durConfig.name)
            }.getOrDefault(emptyList())
            val marking = if (book != null) {
                runCatching { saveMarkingUseCase.findById(markingId) }.getOrNull()
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    highlightRules = rules.toImmutableList(),
                    editing = marking,
                    loading = false,
                )
            }
        }
    }

    /**
     * Sheet 会话期间样式选择的实时预览。只在会话打开时接受（新建=划词选区，
     * 编辑=点正文划线时画布已建的标记选区；从目录进入无选区，画布自然不绘制）。
     * 保存提交等待期（[previewCommitPending] 非空）内忽略，避免撤掉粘性预览。
     */
    fun preview(style: TextProcessStyle) {
        if (previewCommitPending != null) return
        val current = _uiState.value
        if (current.selection == null && current.editing == null) return
        if (current.previewStyle == style) return
        _uiState.update { it.copy(previewStyle = style) }
    }

    fun save(style: TextProcessStyle, note: String) {
        val current = _uiState.value
        val book = ReadBook.book ?: return
        // 先把最终样式钉成预览：持久化 + 重排 + 批次提交完成前一直保持，
        // 关闭弹层时画面已被预览盖住，不会闪回旧页。
        _uiState.update { it.copy(previewStyle = style) }
        previewCommitPending =
            current.selection?.chapterIndex
                ?: current.editing?.chapterIndex
                ?: ReadBook.durChapterIndex
        scope.launch(IO) {
            runCatching {
                persistMarking(current, book, style, note)
            }.onSuccess {
                host.reloadCurrentChapter()
                host.dismissMarkingSheet()
            }.onFailure { error ->
                previewCommitPending = null
                _uiState.update { it.copy(previewStyle = null) }
                host.showToast(error.localizedMessage ?: context.getString(R.string.error))
            }
        }
    }

    /** 删除标记：预览立即撤掉，剩余「旧页还带着划线」的窗口由重排批次提交消除。 */
    fun deleteCurrent() {
        val editing = _uiState.value.editing ?: return
        previewCommitPending = null
        _uiState.update { it.copy(previewStyle = null) }
        scope.launch(IO) {
            runCatching {
                saveMarkingUseCase.delete(editing.id)
            }.onSuccess {
                host.reloadCurrentChapter()
                host.dismissMarkingSheet()
                // 预览在删除发起前就已撤掉，没有等重排批次的理由：选区当场取消，
                // 否则关闭弹层后框着的只是普通文字。
                host.cancelReaderSelection()
            }.onFailure { error ->
                host.showToast(error.localizedMessage ?: context.getString(R.string.error))
            }
        }
    }

    /**
     * 悬浮锚点由宿主在打开弹层的 intent（[openForEdit]/[saveQuick]）之后上报：
     * 两者都会先清空锚点，先到会把本次位置抹掉。目录等无正文位置的入口不上报，
     * 保持标准底部弹层。传 null 表示清除位置，弹层回到标准底部样式。
     */
    fun setSheetAnchor(anchor: ReaderSelectionMenuAnchor?) {
        _uiState.update { it.copy(floatingAnchor = anchor) }
    }

    /** 当前章重排批次提交：新样式已烘进页面，撤掉粘性预览，选区也不再需要留着。 */
    fun onPagesCommitted(chapterIndex: Int) {
        if (previewCommitPending != chapterIndex) return
        previewCommitPending = null
        _uiState.update { it.copy(previewStyle = null) }
        host.cancelReaderSelection()
    }

    fun onSheetDismissed() {
        previewCommitPending = null
        _uiState.value = MarkingUiState()
        host.cancelReaderSelection()
    }

    private suspend fun persistMarking(
        current: MarkingUiState,
        book: Book,
        style: TextProcessStyle,
        note: String,
    ): BookMarking {
        val marking = current.editing
        if (marking != null) {
            // 编辑模式沿用已有锚点与源指纹，只更新样式和备注。
            val anchor = marking.anchor() ?: error("marking anchor missing")
            return saveMarkingUseCase.save(
                bookName = book.name,
                bookAuthor = book.author,
                bookUrl = marking.bookUrl,
                chapterIndex = marking.chapterIndex ?: anchor.chapterIndex,
                chapterPosition = anchor.chapterPosition ?: 0,
                selectedText = anchor.selectedText,
                style = style,
                contextBefore = anchor.contextBefore,
                contextAfter = anchor.contextAfter,
                chapterName = marking.chapterName,
                note = note,
            )
        }

        val selection = current.selection ?: error("marking selection missing")
        val (contextBefore, contextAfter) = selectionContext(selection)
        return saveMarkingUseCase.save(
            bookName = book.name,
            bookAuthor = book.author,
            bookUrl = book.bookUrl,
            chapterIndex = selection.chapterIndex,
            chapterPosition = selection.chapterPos,
            selectedText = selection.bookText,
            style = style,
            contextBefore = contextBefore,
            contextAfter = contextAfter,
            chapterName = selection.chapterName,
            note = note,
        )
    }

    private fun BookMarking.anchor(): TextProcessAnchor? =
        GSON.fromJsonObject<TextProcessAnchor>(anchorJson).getOrNull()

    /**
     * 将选中文本附近的正文一并存入锚点，供换源后的文本匹配消歧。
     * 位置来自当前已排版章节；若选区位置和合成正文略有偏差，则在附近窗口寻找选中文本。
     */
    private fun selectionContext(selection: Bookmark): Pair<String, String> {
        val chapter = ReadBook.readerChapterInputWindow.current
            ?.takeIf { it.chapter.index == selection.chapterIndex }
            ?: return "" to ""
        val content = chapter.source.semanticContent
        val expectedStart = selection.chapterPos.coerceIn(0, content.length)
        val windowStart = max(0, expectedStart - CONTEXT_SEARCH_WINDOW)
        val nearStart = content.indexOf(selection.bookText, windowStart)
            .takeIf { it >= 0 && it <= min(content.length, expectedStart + CONTEXT_SEARCH_WINDOW) }
            ?: expectedStart
        val end = min(content.length, nearStart + selection.bookText.length)
        return content.substring(max(0, nearStart - MARKING_CONTEXT_CHARS), nearStart) to
                content.substring(end, min(content.length, end + MARKING_CONTEXT_CHARS))
    }

    private companion object {
        const val MARKING_CONTEXT_CHARS = 48
        const val CONTEXT_SEARCH_WINDOW = 256
    }
}
