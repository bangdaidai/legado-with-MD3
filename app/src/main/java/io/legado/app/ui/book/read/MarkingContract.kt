package io.legado.app.ui.book.read

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.HighlightRule
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.feature.reader.core.selection.ReaderSelectionMenuAnchor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * 划线/高亮笔记域的状态契约。
 *
 * 该域无独立屏幕，只承载一次「选中文段 → 配置样式/备注 → 保存」的临时会话：
 * [selection] 是当前选中的文段（与普通书签同构），[highlightRules] 供 Sheet 复用样式预设，
 * [editing] 是同一锚点已有的标记（再次选中划线时预填样式与备注进入编辑模式）。
 */
@Immutable
@Stable
data class MarkingUiState(
    val selection: Bookmark? = null,
    val editing: BookMarking? = null,
    val highlightRules: ImmutableList<HighlightRule> = persistentListOf(),
    /** 编辑模式异步加载标记期间的占位，避免 Sheet 先空再弹内容。 */
    val loading: Boolean = false,
    /**
     * 选区实时预览样式：Sheet 会话期间由画布盖在选区上绘制（不改正文、不重排）。
     * 保存后不能随弹层关闭立即撤掉——新样式要等整章重排批次提交才会烘进页面，
     * 提前的空隙会露出旧页（先消失/先回旧样式的闪变）。由 [MarkingDelegate] 在
     * 当前章批次提交后清除；期间选区一旦被点击取消，画布自行停绘。
     */
    val previewStyle: TextProcessStyle? = null,
    /**
     * 阅读页悬浮面板锚点（画布坐标系）：非空时笔记弹层挂在笔记/选区旁边
     * 而不是底部弹层，改样式时能直接看到正文里的效果。从目录等无位置入口
     * 进入时为空，保持底部弹层。
     */
    val floatingAnchor: ReaderSelectionMenuAnchor? = null,
)
