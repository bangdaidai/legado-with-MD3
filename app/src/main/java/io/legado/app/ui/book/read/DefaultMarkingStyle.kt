package io.legado.app.ui.book.read

import io.legado.app.constant.PreferKey
import io.legado.app.domain.model.MarkingEffect
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 笔记（book_marks）专属的默认划线样式读写。
 *
 * 点「笔记」直接用这套样式落库、不弹样式选择；点已有划线才打开
 * [io.legado.app.ui.book.read.sheet.MarkingSheet] 编辑。
 * 与「高亮规则」（正则自动高亮 HighlightRule）无关，是划线笔记独立的默认。
 * 未设置时回落内置默认：实线下划线 + [MarkingEffect.DEFAULT_COLOR]（绿色）。
 *
 * 走 [AppConfigStore] 而非 getPref/putPref 扩展：后者是被配置架构护栏冻结的旧偏好入口。
 */
object DefaultMarkingStyle {

    private val builtIn: TextProcessStyle
        get() = MarkingEffect.SOLID.toStyle(MarkingEffect.DEFAULT_COLOR)

    fun get(): TextProcessStyle {
        val json = AppConfigStore.getString(PreferKey.defaultMarkingStyle)
        if (json.isNullOrBlank()) return builtIn
        return GSON.fromJsonObject<TextProcessStyle>(json).getOrNull() ?: builtIn
    }

    fun set(style: TextProcessStyle) {
        AppConfigStore.putString(PreferKey.defaultMarkingStyle, GSON.toJson(style))
    }

    /**
     * 荧光笔线宽（dp）。全局值：所有荧光笔标记共用，改了对已有标记一起生效。
     *
     * 之所以不跟颜色、效果一起记进每条 book_marks 的 styleJson：色带的粗细和高度是
     * 「这支笔长什么样」，不是某一条笔记的属性，逐条存会出现同一本书里粗细不一。
     * 渲染时由 TextChapterLayout.toCharStyle 统一覆盖成这里的值。
     *
     * 下限不能低于 [MarkingEffect.HIGHLIGHTER_WIDTH_MIN]，否则存下的样式会被
     * [MarkingEffect.fromStyle] 反推成普通单实线（两者同为 underlineMode=1，靠线宽区分）。
     */
    var highlighterWidth: Float
        get() = AppConfigStore.getFloat(PreferKey.highlighterWidth)
            ?: MarkingEffect.HIGHLIGHTER_WIDTH
        set(value) = AppConfigStore.putFloat(
            PreferKey.highlighterWidth,
            value.coerceAtLeast(MarkingEffect.HIGHLIGHTER_WIDTH_MIN),
        )

    /** 荧光笔纵向偏移（dp）。全局值，负数把色带从行底抬进文字里；见 [highlighterWidth]。 */
    var highlighterOffset: Float
        get() = AppConfigStore.getFloat(PreferKey.highlighterOffset)
            ?: MarkingEffect.HIGHLIGHTER_OFFSET
        set(value) = AppConfigStore.putFloat(PreferKey.highlighterOffset, value)
}
