package io.legado.app.ui.book.toc.rule.preview

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class TxtTocRulePreviewUiState(
    val loading: Boolean = true,
    // 模式：网络书籍 or TXT 本地书。默认 TXT，避免影响既有阅读界面入口。
    val isTxt: Boolean = true,
    val rules: ImmutableList<TocRulePreviewItem> = persistentListOf(),
    val currentRule: String = "",
    val selectedRule: String = "",
    val activeSheet: TxtTocRulePreviewSheet? = null,
    val isGridLayout: Boolean = true,
    val editingRule: TxtTocRule? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
    // ===== 网络书籍字段 =====
    // 是否启用替换净化（与阅读页目录展示口径一致）
    val useReplace: Boolean = true,
    // 章节总数（页头展示）
    val chapterTotal: Int = 0,
    // 作用于标题的替换净化规则数量
    val titleReplaceRuleCount: Int = 0,
    // 各标题替换规则在本书缓存目录上的命中情况
    val networkRuleItems: ImmutableList<NetworkRulePreviewItem> = persistentListOf(),
    // 替换规则“链条”示范
    val chainDemo: ChainDemo? = null,
    // 空目录提示（网络书无缓存目录时）
    val emptyHint: String = "",
) {
    val hasSelection: Boolean get() = selectedRule.isNotEmpty()
    val filteredRules: ImmutableList<TocRulePreviewItem>
        get() = if (searchQuery.isBlank()) rules
        else rules.filter {
            it.rule.name.contains(searchQuery, ignoreCase = true) ||
                    it.rule.example?.contains(searchQuery, ignoreCase = true) == true ||
                    it.rule.chapterRule.contains(searchQuery, ignoreCase = true)
        }.toImmutableList()

    val filteredNetworkRules: ImmutableList<NetworkRulePreviewItem>
        get() = if (searchQuery.isBlank()) networkRuleItems
        else networkRuleItems.filter {
            it.rule.name.contains(searchQuery, ignoreCase = true) ||
                    it.rule.pattern.contains(searchQuery, ignoreCase = true) ||
                    it.example?.contains(searchQuery, ignoreCase = true) == true
        }.toImmutableList()
}

@Stable
data class TocRulePreviewItem(
    val rule: TxtTocRule,
    val chapterCount: Int = 0,
    val totalCount: Int = 0,
    val chapters: ImmutableList<String> = persistentListOf(),
)

@Stable
data class NetworkRulePreviewItem(
    val rule: ReplaceRule,
    // 在替换链条中的执行顺序（从 1 开始）
    val order: Int = 0,
    // 命中（改变）的章节数
    val matchCount: Int = 0,
    // 本书章节总数
    val totalChapter: Int = 0,
    // 命中的章节样本（原标题 to 替换后标题），最多 200 条
    val chapters: ImmutableList<Pair<String, String>> = persistentListOf(),
    // 替换示例（原标题 → 替换后标题）
    val example: String? = null,
    // 是否已完成命中统计（未完成时卡片展示加载中）
    val computed: Boolean = true,
)

/**
 * 替换规则“链条”中的单步：一条标题进入该规则前/经过该规则后。
 */
@Stable
data class ChainStep(
    val ruleId: Long,
    val ruleName: String,
    val before: String,
    val after: String,
    val changed: Boolean,
)

/**
 * 展示“替换规则链式接力”的示范链：选一条被改变次数最多的章节，
 * 从原始标题开始，依次经过每条规则得到最终标题。
 */
@Stable
data class ChainDemo(
    val originalTitle: String,
    val finalTitle: String,
    val steps: ImmutableList<ChainStep> = persistentListOf(),
) {
    val changedStepCount: Int get() = steps.count { it.changed }
}

sealed interface TxtTocRulePreviewSheet {
    data class ChapterList(val item: TocRulePreviewItem) : TxtTocRulePreviewSheet
    data class NetworkRuleChapters(val item: NetworkRulePreviewItem) : TxtTocRulePreviewSheet
}

sealed interface TxtTocRulePreviewIntent {
    data object DismissSheet : TxtTocRulePreviewIntent
    data class ShowChapterList(val item: TocRulePreviewItem) : TxtTocRulePreviewIntent
    data class SelectRule(val rule: String) : TxtTocRulePreviewIntent
    data object ToggleLayout : TxtTocRulePreviewIntent
    data object OpenManagePage : TxtTocRulePreviewIntent
    data class EditRule(val rule: TxtTocRule) : TxtTocRulePreviewIntent
    data object DismissEditDialog : TxtTocRulePreviewIntent
    data class SaveRule(val rule: TxtTocRule) : TxtTocRulePreviewIntent
    data object ToggleSearch : TxtTocRulePreviewIntent
    data class UpdateSearchQuery(val query: String) : TxtTocRulePreviewIntent
    data object ApplyRule : TxtTocRulePreviewIntent
    // ===== 网络书籍 =====
    // 预览单条标题替换规则在本书的命中效果
    data class ShowNetworkRuleChapters(val item: NetworkRulePreviewItem) : TxtTocRulePreviewIntent
    // 打开某条标题替换规则的编辑页面
    data class EditNetworkRule(val ruleId: Long) : TxtTocRulePreviewIntent
    // 规则被编辑后，重新统计
    data object Refresh : TxtTocRulePreviewIntent
}

sealed interface TxtTocRulePreviewEffect {
    data class ShowToast(val message: String) : TxtTocRulePreviewEffect
    data object OpenManagePage : TxtTocRulePreviewEffect
    data class ApplyRule(val rule: String) : TxtTocRulePreviewEffect
    // 打开替换规则编辑页（网络书籍规则预览用）
    data class OpenReplaceRuleEditor(val ruleId: Long) : TxtTocRulePreviewEffect
}
