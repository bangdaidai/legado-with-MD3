package io.legado.app.model

import io.legado.app.data.entities.BookMarking

/**
 * 当前阅读会话的划线备注快照，供渲染层同步判定「这条划线要不要在正文里画备注角标」。
 *
 * 与 [ReaderBookmarkState] 同一形态：渲染层（`LegacyReaderPageDecorationFactory`）在主线程
 * 上为每页算装饰，不能起协程查库，所以由 `MarkingDelegate` 收集
 * `BookMarkingGateway.flowByBook` 后整份写进来。
 *
 * 只读缓存，不参与持久化。快照携带 `bookName to bookAuthor` 书键：查快照都带键，
 * 键不一致视为无备注。这样换书后、新书备注流第一条数据到达之前，上一本残留的旧快照
 * 不会给新书凭空画出角标（旧键 vs 新查询键 → 失配 → 不画）。
 *
 * 只留**有备注**的划线：正文里出角标的前提就是有备注可看，没有备注的划线只画线。
 */
object ReaderMarkingNoteState {

    private data class Snapshot(
        val bookName: String,
        val bookAuthor: String,
        /** 划线 id → 备注原文。只含启用且备注非空的行。 */
        val notes: Map<String, String>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** 当前书的「划线 id → 备注」；书键不匹配时返回空表（调用方无需再判键）。 */
    fun notesFor(bookName: String, bookAuthor: String): Map<String, String> {
        val snap = snapshot ?: return emptyMap()
        return if (snap.bookName == bookName && snap.bookAuthor == bookAuthor) snap.notes else emptyMap()
    }

    /**
     * 按划线 id 取备注原文（角标浮窗用）。
     *
     * 不再校书键：能画出来的角标本来就出自当前书快照，同一时刻的快照里必然有这条备注。
     */
    fun noteFor(markingId: String): String? = snapshot?.notes?.get(markingId)

    fun update(bookName: String, bookAuthor: String, markings: List<BookMarking>) {
        snapshot = Snapshot(
            bookName = bookName,
            bookAuthor = bookAuthor,
            notes = markings
                .filter { it.enabled && it.note.isNotBlank() }
                .associate { it.id to it.note },
        )
    }

    fun clear() {
        snapshot = null
    }
}