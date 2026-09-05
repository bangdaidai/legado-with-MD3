package io.legado.app.ui.book.knowledge

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.BookCharacterProfile

/** 人物速查（划词 → 是谁）的界面契约：三级漏斗 = 本地档案命中 → 全文检索锚点 → AI 归纳。 */
@Stable
data class CharacterQueryUiState(
    val name: String = "",
    val isLoading: Boolean = false,
    /** 第一级：本地人物档案命中时非空，命中后不需要再问 AI。 */
    val profile: BookCharacterProfile? = null,
    val firstAppearance: CharacterAppearance? = null,
    val latestAppearance: CharacterAppearance? = null,
    /** 章节缓存是否覆盖全书；不完整时提示「最初登场」可能更早。 */
    val searchFullyCovered: Boolean = true,
    /** 第三级：AI 归纳的介绍文字，档案命中时为空。 */
    val aiSummary: String = "",
    val aiLoading: Boolean = false,
    val error: String? = null,
    val saveState: SaveState = SaveState.Idle,
) {
    @Stable
    data class CharacterAppearance(
        val chapterIndex: Int,
        val chapterTitle: String,
        val excerpt: String,
    )

    enum class SaveState { Idle, Saving, Saved, Failed }
}

sealed interface CharacterQueryIntent {
    data class Load(val name: String) : CharacterQueryIntent
    data object Retry : CharacterQueryIntent
    data class JumpTo(val chapterIndex: Int) : CharacterQueryIntent
    data object SaveProfile : CharacterQueryIntent
}
