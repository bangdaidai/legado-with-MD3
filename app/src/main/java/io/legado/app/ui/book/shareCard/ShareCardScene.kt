package io.legado.app.ui.book.shareCard

import androidx.annotation.StringRes
import io.legado.app.R

/**
 * 分享卡片的生成场景。每个场景在「分组管理」里可绑定一个或多个模板分组，
 * 该场景弹出的预览面板只显示所绑分组的模板（并集）；未绑定任何分组时回退显示全部。
 */
enum class ShareCardScene(
    val key: String,
    @StringRes val labelRes: Int,
) {
    SELECTION("selection", R.string.share_card_scene_selection),
    NOTE("note", R.string.share_card_scene_note),
    READING_MEMORY("reading_memory", R.string.share_card_scene_reading_memory),
    STATS("stats", R.string.share_card_scene_stats),
    ;

    companion object {
        val all: List<ShareCardScene> get() = entries
    }
}
