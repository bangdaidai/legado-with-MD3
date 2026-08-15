package io.legado.app.ui.book.read

import io.legado.app.constant.PreferKey
import io.legado.app.domain.model.MarkingEffect
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 笔记（book_marks）专属的默认划线样式读写。
 *
 * 点「笔记」直接用这套样式落库、不弹样式选择；点已有划线才打开 [io.legado.app.ui.book.read.sheet.MarkingSheet] 编辑。
 * 与「高亮规则」（正则自动高亮 HighlightRule）无关，是划线笔记独立的默认。
 * 未设置时回落内置默认：实线下划线 + [MarkingEffect.DEFAULT_COLOR]（绿色）。
 */
object DefaultMarkingStyle {

    private val builtIn: TextProcessStyle
        get() = MarkingEffect.SOLID.toStyle(MarkingEffect.DEFAULT_COLOR)

    fun get(): TextProcessStyle {
        val json = appCtx.getPrefString(PreferKey.defaultMarkingStyle)
        if (json.isNullOrBlank()) return builtIn
        return GSON.fromJsonObject<TextProcessStyle>(json).getOrNull() ?: builtIn
    }

    fun set(style: TextProcessStyle) {
        appCtx.putPrefString(PreferKey.defaultMarkingStyle, GSON.toJson(style))
    }
}
