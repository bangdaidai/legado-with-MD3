package io.legado.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.legado.app.ui.theme.hazeStyle.HazeLegado
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自动感知全局配置的 HazeSource
 */
@Composable
fun Modifier.responsiveHazeSource(state: HazeState): Modifier = this.then(
    if (LocalAppUiConfiguration.current.theme.enableBlur) Modifier.hazeSource(state) else Modifier
)

/**
 * 自动感知全局配置的 HazeEffect
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.responsiveHazeEffect(
    state: HazeState
): Modifier {
    val themeSettings = LocalAppUiConfiguration.current.theme
    val enableBlur = themeSettings.enableBlur
    val enableProgressiveBlur = themeSettings.enableProgressiveBlur
    val composeEngine = LegadoTheme.composeEngine
    val containerColor = GlassDefaults.secondaryColorOr {
        if (ThemeResolver.isMiuixEngine(composeEngine)) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    }

    if (!enableBlur) return this

    val style = HazeLegado.custom(
        containerColor = containerColor,
        blurRadius = themeSettings.topBarBlurRadius,
        blurAlpha = themeSettings.topBarBlurAlpha
    )

    return this.hazeEffect(
        state = state,
        style = style
    ) {
        progressive = if (enableProgressiveBlur) {
            HazeProgressive.verticalGradient(
                startIntensity = 1f,
                endIntensity = 0f
            )
        } else {
            null
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.responsiveHazeEffectFixedStyle(
    state: HazeState
): Modifier {
    val enableBlur = LocalAppUiConfiguration.current.theme.enableBlur
    val composeEngine = LegadoTheme.composeEngine
    val containerColor = GlassDefaults.secondaryColorOr {
        if (ThemeResolver.isMiuixEngine(composeEngine)) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    }

    if (!enableBlur) return this

    val style = HazeLegado.ultraThinPlus(containerColor = containerColor)

    return this.hazeEffect(
        state = state,
        style = style
    ) {
        progressive =
            HazeProgressive.verticalGradient(
                startIntensity = 1f,
                endIntensity = 0f
            )
    }
}

/**
 * 状态栏独立模糊：仅在全局「控件模糊」关闭时作为状态栏区域的补偿层生效，固定为渐变模糊。
 * 全局模糊开启时状态栏区域由顶栏自身的模糊负责，这里不再叠加，保持与上游一致的观感。
 * 与全局「控件模糊」的顶栏风格保持一致（零色膜 ultraThinPlus），只做渐变模糊、不叠白膜。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.responsiveHazeEffectStatusBar(state: HazeState): Modifier {
    val themeSettings = LocalAppUiConfiguration.current.theme
    if (themeSettings.enableBlur || !themeSettings.enableStatusBarBlur) return this

    val composeEngine = LegadoTheme.composeEngine
    val containerColor = GlassDefaults.secondaryColorOr {
        if (ThemeResolver.isMiuixEngine(composeEngine)) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    }
    // 与全局「控件模糊」的顶栏风格保持一致：用零色膜的 ultraThinPlus 只做模糊、不叠白膜。
    // 模糊框高度即状态栏高度：渐进 1f→0f 从状态栏顶部满模糊、到底部归零，
    // 与下方无模糊内容自然衔接，不外扩成额外缓冲带。
    val style = HazeLegado.ultraThinPlus(containerColor = containerColor)
    return this.hazeEffect(state = state, style = style) {
        progressive = HazeProgressive.verticalGradient(
            startIntensity = 1f,
            endIntensity = 0f
        )
    }
}

/**
 * 仅判断 enableBlur 的简单 HazeEffect
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.regularHazeEffect(state: HazeState): Modifier {
    val enableBlur = LocalAppUiConfiguration.current.theme.enableBlur
    val composeEngine = LegadoTheme.composeEngine
    val containerColor = GlassDefaults.secondaryColorOr {
        if (ThemeResolver.isMiuixEngine(composeEngine)) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    }

    if (!enableBlur) return this

    return this.hazeEffect(
        state = state,
        style = HazeLegado.ultraThin(containerColor = containerColor)
    )
}
