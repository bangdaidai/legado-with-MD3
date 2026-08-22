package io.legado.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme.composeEngine

@Composable
fun Modifier.adaptiveHorizontalPadding(): Modifier {
    val horizontal = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp
    return this.padding(horizontal = horizontal)
}

/** 屏幕标准水平边距的原始数值，供需要自行排布(如自绘卡片)的场景对齐使用。 */
@Composable
fun adaptiveHorizontalPaddingValue(): Dp =
    if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp

@Composable
fun Modifier.adaptiveHorizontalPaddingTab(): Modifier {
    val start = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 0.dp
    val end = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp
    return this.padding(start = start, end = end)
}

@Composable
fun Modifier.adaptiveHorizontalPadding(
    vertical: Dp,
): Modifier {
    val horizontal = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp
    return this.padding(horizontal = horizontal, vertical = vertical)
}

@Composable
fun Modifier.adaptiveVerticalPadding(): Modifier {
    val horizontal = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 8.dp
    return this.padding(horizontal = horizontal)
}

@Composable
fun adaptiveHorizonalPadding(): PaddingValues {
    val horizontal = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp
    return PaddingValues(
        horizontal = horizontal
    )
}

@Composable
fun adaptiveContentPaddingOnlyVertical(
    top: Dp,
    bottom: Dp
): PaddingValues {
    val adjustedTop = if (ThemeResolver.isMiuixEngine(composeEngine)) top + 8.dp else top
    return PaddingValues(
        top = adjustedTop,
        bottom = bottom,
        start = 0.dp,
        end = 0.dp
    )
}

/**
 * 正文内容距标题栏的标准间距。M3 没有给死数值，项目统一取 8dp：列表/卡片流本身还带 4dp 的行间距，
 * 再多就明显头重。横向仍按引擎区分 16dp(M3) / 12dp(Miuix)。
 */
private val ContentTopGap = 8.dp

@Composable
fun adaptiveContentPadding(
    top: Dp,
    bottom: Dp
): PaddingValues {
    val horizontal = if (ThemeResolver.isMiuixEngine(composeEngine)) 12.dp else 16.dp
    return PaddingValues(
        top = top + ContentTopGap,
        bottom = bottom,
        start = horizontal,
        end = horizontal
    )
}

@Composable
fun adaptiveContentPadding(
    top: Dp,
    bottom: Dp,
    miuixHorizontal: Dp,
    m3Horizontal: Dp
): PaddingValues {
    val horizontal =
        if (ThemeResolver.isMiuixEngine(composeEngine)) miuixHorizontal else m3Horizontal
    return PaddingValues(
        top = top + ContentTopGap,
        bottom = bottom,
        start = horizontal,
        end = horizontal
    )
}

@Composable
fun adaptiveContentPadding(
    top: Dp,
    bottom: Dp,
    horizontal: Dp
): PaddingValues {
    return PaddingValues(
        top = top + ContentTopGap,
        bottom = bottom,
        start = horizontal,
        end = horizontal
    )
}

@Composable
fun adaptiveContentPaddingBookshelf(
    top: Dp,
    bottom: Dp,
    horizontal: Dp
): PaddingValues {
    val horizontal =
        if (ThemeResolver.isMiuixEngine(composeEngine)) 6.dp + horizontal else 4.dp + horizontal
    return PaddingValues(
        top = top + ContentTopGap,
        bottom = bottom,
        start = horizontal,
        end = horizontal
    )
}
