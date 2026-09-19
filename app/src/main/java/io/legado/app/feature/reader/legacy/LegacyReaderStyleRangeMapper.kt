package io.legado.app.feature.reader.legacy

import io.legado.app.data.entities.BookContentProcess
import io.legado.app.data.entities.HighlightRule
import io.legado.app.domain.model.BookContentProcessEngine
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.feature.reader.core.model.ReaderTextBackgroundImage
import io.legado.app.feature.reader.core.model.ReaderUnderline
import io.legado.app.feature.reader.core.model.withBitmapSize
import io.legado.app.feature.reader.core.source.ReaderChapterInlineSource
import io.legado.app.feature.reader.core.source.ReaderChapterSource
import io.legado.app.feature.reader.core.source.ReaderChapterSourceBlock
import io.legado.app.feature.reader.core.style.ReaderCharacterStyle
import io.legado.app.feature.reader.core.style.ReaderStyleRange
import io.legado.app.feature.reader.core.style.ReaderStyleTarget
import io.legado.app.feature.reader.platform.ReaderTextBackgroundLoader
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadStyleResolver
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.spToPx

object LegacyReaderStyleRangeMapper {
    fun map(
        source: ReaderChapterSource,
        rules: List<HighlightRule>,
        processes: List<BookContentProcess>,
        bookUrl: String,
    ): List<ReaderStyleRange> {
        val result = mutableListOf<ReaderStyleRange>()
        val bodyText = semanticBodyText(source)
        val titleText = source.semanticTitle
        var ruleIndex = 0
        rules.filter(HighlightRule::enabled).forEach { rule ->
            // 单条规则的任何异常都只跳过自己，绝不冒泡拖垮整章排版：
            // 与旧引擎"该条展开/解析失败就整条跳过"一致。旧引擎不崩正是因为逐条容错，
            // 新引擎此前缺这层保护——一本配了坏规则的书会点"重试"仍反复失败。
            val priority = ruleIndex++
            runCatching {
                // 跟随主角：把人物名/别名展开成正则；展开失败（该书无人）时整条规则跳过
                val pattern = if (rule.useProtagonist) {
                    HighlightProtagonistPatterns.patternFor(bookUrl, rule.characterRole)
                        ?: return@runCatching
                } else {
                    rule.pattern
                }
                val regex = runCatching { Regex(pattern) }.getOrNull() ?: return@runCatching
                val targets = when (rule.targetScope) {
                    HighlightRule.TARGET_TITLE -> listOf(titleText to ReaderStyleTarget.TITLE)
                    HighlightRule.TARGET_BODY -> listOf(bodyText to ReaderStyleTarget.BODY)
                    else -> listOf(titleText to ReaderStyleTarget.TITLE, bodyText to ReaderStyleTarget.BODY)
                }
                val style = runCatching { rule.toReaderStyle() }.getOrNull() ?: return@runCatching
                targets.forEach { (text, target) ->
                    regex.findAll(text).forEach { match ->
                        result += ReaderStyleRange(
                            start = match.range.first,
                            endExclusive = match.range.last + 1,
                            target = target,
                            style = style,
                            priority = priority,
                        )
                    }
                }
            }
        }
        processes.asSequence()
            .filter { it.enabled && it.status == BookContentProcess.STATUS_ACTIVE && it.isUserMarking() }
            .forEachIndexed { index, process ->
                val anchor = GSON.fromJsonObject<TextProcessAnchor>(process.anchorJson).getOrNull()
                    ?: return@forEachIndexed
                val markingStyle = GSON.fromJsonObject<TextProcessStyle>(process.styleJson).getOrNull()
                    ?: return@forEachIndexed
                val range = BookContentProcessEngine.resolveRange(bodyText, anchor) ?: return@forEachIndexed
                result += ReaderStyleRange(
                    start = range.first,
                    endExclusive = range.last + 1,
                    target = ReaderStyleTarget.BODY,
                    style = markingStyle.toReaderStyle(process.id.removePrefix("mark:")),
                    priority = 10_000 + index,
                )
            }
        return result
    }

    private fun semanticBodyText(source: ReaderChapterSource): String {
        val chars = CharArray(source.characterCount) { '\n' }
        source.blocks.forEach { block ->
            when (block) {
                is ReaderChapterSourceBlock.Text -> if (!block.isTitle) {
                    chars.writeText(block.chapterPosition, block.value)
                }
                is ReaderChapterSourceBlock.Image -> chars.setOrNull(block.chapterPosition, '\uFFFC')
                is ReaderChapterSourceBlock.Paragraph -> block.items.forEach { item ->
                    when (item) {
                        is ReaderChapterInlineSource.Text -> chars.writeText(item.chapterPosition, item.value)
                        is ReaderChapterInlineSource.Image -> chars.setOrNull(item.chapterPosition, '\uFFFC')
                        is ReaderChapterInlineSource.BlankLine -> Unit
                    }
                }
                is ReaderChapterSourceBlock.Html, is ReaderChapterSourceBlock.PageBreak -> Unit
            }
        }
        return chars.concatToString()
    }

    private fun CharArray.setOrNull(index: Int, value: Char) {
        if (index in indices) this[index] = value
    }

    private fun CharArray.writeText(start: Int, text: String) {
        if (start !in indices || text.isEmpty()) return
        val count = minOf(text.length, size - start)
        for (offset in 0 until count) this[start + offset] = text[offset]
    }

    private fun HighlightRule.toReaderStyle() = ReaderCharacterStyle(
        colorArgb = resolveModeColor(textColor, textColorNight, hasBgImage = false),
        backgroundArgb = resolveModeColor(bgColor, bgColorNight, bgImage?.isNotBlank() == true),
        underline = underlineMode.takeIf { it != 0 }?.let {
            ReaderUnderline(
                mode = it,
                // 兜底跟随正文色：旧 `TextLine.drawStyledUnderlines` 的
                // `underlineColor ?: textColor ?: ChapterProvider.renderStyle.textColor`
                // （即 ReadBookConfig.textColor），不是写死的主题绿。
                colorArgb = resolveModeColor(
                    underlineColor ?: textColor,
                    underlineColorNight ?: textColorNight,
                    bgImage?.isNotBlank() == true,
                ) ?: ReadBookConfig.textColor,
                widthPx = underlineWidth.dpToPx(),
                offsetPx = underlineOffset.dpToPx(),
                svgPath = underlineSvgPath.orEmpty(),
                dashOnPx = underlineDashLen.dpToPx(),
                dashOffPx = underlineDashGap.dpToPx(),
                waveAmplitudePx = 3f.dpToPx(),
                waveLengthPx = 12f.dpToPx(),
                doubleLineGapPx = 3f.dpToPx(),
                roundCap = underlineRoundCap,
                featherPx = underlineFeather.dpToPx(),
                belowText = underlineBelowText,
            )
        },
        fontPath = fontPath,
        // 400 is the persisted/default "regular" value from the View reader, where an empty
        // font override left the body Paint untouched.  Passing it as an explicit override in
        // the new renderer reset bold/light body text to regular.  Keep it unset so the body
        // style remains the source of truth; non-default weights still override it.
        fontWeight = fontWeight.takeIf { it != 400 },
        italic = isItalic,
        fontSizeOffsetPx = fontSizeOffset.toFloat().spToPx(),
        backgroundImage = bgImage?.takeIf(String::isNotBlank)?.let {
            val automatic = if (manualNineSlice) null else {
                ReaderTextBackgroundLoader.nineSliceFractions(it)
            }
            ReaderTextBackgroundImage(
                source = it,
                fit = bgImageFit,
                scale = bgImageScale,
                ninePatchLeft = automatic?.left ?: npLeft,
                ninePatchRight = automatic?.right ?: npRight,
                ninePatchTop = automatic?.top ?: npTop,
                ninePatchBottom = automatic?.bottom ?: npBottom,
                paddingLeftPx = bgPaddingStart.dpToPx(),
                paddingRightPx = bgPaddingEnd.dpToPx(),
                paddingTopPx = bgPaddingTop.dpToPx(),
                paddingBottomPx = bgPaddingBottom.dpToPx(),
                marginStartPx = bgMarginStart.dpToPx(),
                marginEndPx = bgMarginEnd.dpToPx(),
            ).let { image ->
                val (width, height) = backgroundImageSize(it)
                image.withBitmapSize(width, height)
            }
        },
    )

    private fun TextProcessStyle.toReaderStyle(markingId: String) = ReaderCharacterStyle(
        // 笔记样式没有夜间字段，旧引擎在夜间按明度反相派生（TextColumn/TextLine 的
        // resolveModeColor 传入 null 夜间色即走派生分支），这里保持同一语义。
        colorArgb = resolveModeColor(textColor, nightColor = null, hasBgImage = false),
        backgroundArgb = resolveModeColor(bgColor, nightColor = null, hasBgImage = false),
        underline = underlineMode.takeIf { it != 0 }?.let {
            ReaderUnderline(
                mode = it,
                // 兜底跟随正文色：旧 `TextLine.drawStyledUnderlines` 的
                // `underlineColor ?: textColor ?: ChapterProvider.renderStyle.textColor`
                // （即 ReadBookConfig.textColor），不是写死的主题绿。
                colorArgb = resolveModeColor(
                    underlineColor ?: textColor,
                    nightColor = null,
                    hasBgImage = false,
                ) ?: ReadBookConfig.textColor,
                widthPx = underlineWidth.dpToPx(),
                offsetPx = underlineOffset.dpToPx(),
                svgPath = underlineSvgPath.orEmpty(),
                dashOnPx = 8f.dpToPx(),
                dashOffPx = 5f.dpToPx(),
                waveAmplitudePx = 3f.dpToPx(),
                waveLengthPx = 12f.dpToPx(),
                doubleLineGapPx = 3f.dpToPx(),
            )
        },
        markingId = markingId,
    )

    private fun BookContentProcess.isUserMarking(): Boolean =
        kind == BookContentProcess.KIND_USER_UNDERLINE || kind == BookContentProcess.KIND_USER_HIGHLIGHT

    /**
     * 对照旧 `TextLine`/`TextColumn.resolveModeColor` 的双向派生：
     * 日间优先日间色、缺失时反相夜间色；夜间优先夜间色、缺失时反相日间色
     * （文字色不受 [hasBgImage] 限制；背景/下划线色在配了背景图时不反相，
     * 因为图案底上反相色会突兀）。排版期解析一次，日夜间切换由整章重排消化。
     */
    private fun resolveModeColor(dayColor: Int?, nightColor: Int?, hasBgImage: Boolean): Int? {
        if (!ReadStyleResolver.isNightTheme()) {
            dayColor?.let { return it }
            nightColor?.let { return ColorUtils.flipLightness(it) }
            return null
        }
        nightColor?.let { return it }
        if (hasBgImage) return dayColor
        return dayColor?.let { ColorUtils.flipLightness(it) }
    }

    private fun backgroundImageSize(path: String): Pair<Int, Int> =
        ReaderTextBackgroundLoader.dimensions(path)
}
