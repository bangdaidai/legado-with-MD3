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
 * 状态栏独立模糊：即使全局「控件模糊/渐变模糊」关闭，也可单独开启。
 * 开启全局模糊时沿用全局样式与渐进模糊开关；仅状态栏独立开启时固定为渐变模糊。
 * 模糊半径与透明度始终跟随全局顶栏设置。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.responsiveHazeEffectStatusBar(state: HazeState): Modifier {
    val themeSettings = LocalAppUiConfiguration.current.theme
    val enableBlur = themeSettings.enableBlur
    val enableStatusBarBlur = themeSettings.enableStatusBarBlur
    if (!enableBlur && !enableStatusBarBlur) return this

    val composeEngine = LegadoTheme.composeEngine
    val containerColor = GlassDefaults.secondaryColorOr {
        if (ThemeResolver.isMiuixEngine(composeEngine)) MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    }
    val style = HazeLegado.custom(
        containerColor = containerColor,
        blurRadius = themeSettings.topBarBlurRadius,
        blurAlpha = themeSettings.topBarBlurAlpha,
    )
    val useGradient = if (enableBlur) themeSettings.enableProgressiveBlur else true
    return this.hazeEffect(state = state, style = style) {
        progressive = if (useGradient) {
            HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
        } else {
            null
        }
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
