package io.legado.app.ui.book.toc.rule.preview

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.domain.model.AiTitleCleanRuleDraft
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

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
    // AI 正在读目录生成规则
    val generatingAi: Boolean = false,
) {
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
    // >0 表示这条是 @js: 规则，预览只在前这么多章试跑，命中数是样本内的数字
    val jsSampleLimit: Int = 0,
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

/**
 * AI 依据本书真实标题给出的一条净化规则草稿，附带在本书目录上的实际命中情况。
 * 命中数为 0 的草稿仍然展示，让用户看到模型给错了，而不是静默丢掉。
 * [selected] 用于一次生成里多选采用，默认勾上有命中的草稿。
 */
@Stable
data class AiTitleDraftItem(
    val draft: AiTitleCleanRuleDraft,
    val matchCount: Int = 0,
    val totalChapter: Int = 0,
    val samples: ImmutableList<Pair<String, String>> = persistentListOf(),
    val selected: Boolean = false,
)

sealed interface TxtTocRulePreviewSheet {
    data class ChapterList(val item: TocRulePreviewItem) : TxtTocRulePreviewSheet
    data class NetworkRuleChapters(val item: NetworkRulePreviewItem) : TxtTocRulePreviewSheet
    data class AiTitleDrafts(val items: ImmutableList<AiTitleDraftItem>) : TxtTocRulePreviewSheet
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
    // ===== AI 生成规则 =====
    // 让 AI 读本书真实目录，反推一条规则；TXT 与网络书籍产出不同
    data object GenerateWithAi : TxtTocRulePreviewIntent
    // 勾选/取消勾选某条 AI 草稿
    data class ToggleAiTitleDraft(val index: Int) : TxtTocRulePreviewIntent
    // 一次采用所有勾选的草稿，落库成作用于标题的替换规则
    data object AdoptSelectedAiTitleDrafts : TxtTocRulePreviewIntent
}

sealed interface TxtTocRulePreviewEffect {
    data class ShowToast(val message: String) : TxtTocRulePreviewEffect
    // 打开 txt 目录规则管理页（本地 TXT 模式）
    data object OpenManagePage : TxtTocRulePreviewEffect
    // 打开替换净化管理页（网络书籍模式）
    data object OpenReplaceRuleManagePage : TxtTocRulePreviewEffect
    data class ApplyRule(val rule: String) : TxtTocRulePreviewEffect
    // 打开替换规则编辑页（网络书籍规则预览用）
    data class OpenReplaceRuleEditor(val ruleId: Long) : TxtTocRulePreviewEffect
}
