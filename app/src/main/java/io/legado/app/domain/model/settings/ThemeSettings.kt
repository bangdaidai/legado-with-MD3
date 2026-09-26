package io.legado.app.domain.model.settings

data class ThemeSettings(
    val appTheme: String = "0",
    val useMiuixMonet: Boolean = false,
    val isPureBlack: Boolean = false,
    val paletteStyle: String = "tonalSpot",
    val materialVersion: String = "material3",
    val customContrast: String = "Default",
    val customMode: String = "tonalSpot",
    val appFontPath: String? = null,
    val customPrimary: Int = 0,
    val customNightPrimary: Int = 0,
    val enableDeepPersonalization: Boolean = false,
    val themeColor: Int = 0,
    val secondaryThemeColor: Int = 0,
    val primaryTextColor: Int = 0,
    val secondaryTextColor: Int = 0,
    val themeBackgroundColor: Int = 0,
    val labelContainerColor: Int = 0,
    val themeColorNight: Int = 0,
    val secondaryThemeColorNight: Int = 0,
    val primaryTextColorNight: Int = 0,
    val secondaryTextColorNight: Int = 0,
    val themeBackgroundColorNight: Int = 0,
    val labelContainerColorNight: Int = 0,
    val containerOpacity: Int = 100,
    val overrideBaseCardCornerRadius: Boolean = false,
    val baseCardCornerRadius: Float = 16f,
    val overrideBaseCardBorder: Boolean = false,
    val baseCardBorderWidth: Float = 1f,
    val baseCardBorderColor: Int = 0,
    val baseCardBorderColorNight: Int = 0,
    val disableSplicedColumnGroupCornerRadius: Boolean = false,
    val topBarOpacity: Int = 100,
    val bottomBarOpacity: Int = 100,
    val enableBlur: Boolean = false,
    val enableProgressiveBlur: Boolean = false,
    val enableStatusBarBlur: Boolean = true,
    val topBarBlurRadius: Int = 24,
    val bottomBarBlurRadius: Int = 8,
    val topBarBlurAlpha: Int = 73,
    val bottomBarBlurAlpha: Int = 40,
    val bottomBarLensRadius: Float = 24f,
    val useFlexibleTopAppBar: Boolean = true,
    val topBarButtonStyle: String = "tonal",
    val mergeTopBarActions: Boolean = false,
    val bookInfoFollowCoverColor: Boolean = true,
    val bookInfoNetworkCoverBackground: String = "on",
    val bookInfoDefaultCoverBackground: String = "on",
    val bookInfoInputColor: Int = 0,
    val backgroundImageLight: String? = null,
    val backgroundImageDark: String? = null,
    val backgroundImageBlurring: Int = 0,
    val backgroundImageDarkBlurring: Int = 0,
    val largeContainerBackgroundImageLight: String? = null,
    val largeContainerBackgroundImageDark: String? = null,
    val itemBackgroundImageLight: String? = null,
    val itemBackgroundImageDark: String? = null,
    // 容器背景图的九宫格切分线（与高亮背景图同一口径：四角角块占比，CSV "npL,npR,npT,npB"）。
    // null 表示未在切图编辑器里保存过：.9.png 走引导线自动探测，普通图保持整图平铺。
    val largeContainerNineSliceLight: String? = null,
    val largeContainerNineSliceDark: String? = null,
    val itemNineSliceLight: String? = null,
    val itemNineSliceDark: String? = null,
    // 九宫格图案缩放：四角/四边图案按「原图像素 × scale，1px=1dp 基准」绘制，
    // 不再跟随容器高度自动缩放；日/夜图共用（对齐高亮规则 bgImageScale 的先例）。
    val largeContainerNineSliceScale: Float = 1f,
    val itemNineSliceScale: Float = 1f,
    val enableContainerBackgroundImage: Boolean = false,
    val appColumnBackgroundOpacity: Int = 100,
    val glassCardBackgroundOpacity: Int = 100,
    val enableItemDivider: Boolean = false,
    val itemDividerWidth: Float = 1f,
    val itemDividerLength: Float = 80f,
    val itemDividerColor: Int = 0,
    val eyeProtectionEnabled: Boolean = false,
    val colorTemperature: Int = 50,
    val eyeProtectionAutoNight: Boolean = false,
    val eyeProtectionSchedule: Boolean = false,
    val eyeProtectionStartTime: String = "22:00",
    val eyeProtectionEndTime: String = "07:00",
    val showRefactorTip: Boolean = true,
    val enableCustomTagColors: Boolean = false,
)

val ThemeSettings.isEyeProtectionConfigured: Boolean
    get() = eyeProtectionEnabled || eyeProtectionAutoNight

data class ThemeCustomColors(
    val primary: Int,
    val secondary: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val background: Int,
    val labelContainer: Int,
) {
    val hasCustomColor: Boolean
        get() = primary != 0 || secondary != 0 || primaryText != 0 ||
            secondaryText != 0 || background != 0 || labelContainer != 0
}

fun ThemeSettings.customColors(isDark: Boolean): ThemeCustomColors =
    if (isDark) {
        ThemeCustomColors(
            primary = themeColorNight.takeIf { it != 0 } ?: themeColor,
            secondary = secondaryThemeColorNight.takeIf { it != 0 } ?: secondaryThemeColor,
            primaryText = primaryTextColorNight.takeIf { it != 0 } ?: primaryTextColor,
            secondaryText = secondaryTextColorNight.takeIf { it != 0 } ?: secondaryTextColor,
            background = themeBackgroundColorNight.takeIf { it != 0 } ?: themeBackgroundColor,
            labelContainer = labelContainerColorNight.takeIf { it != 0 } ?: labelContainerColor,
        )
    } else {
        ThemeCustomColors(
            primary = themeColor,
            secondary = secondaryThemeColor,
            primaryText = primaryTextColor,
            secondaryText = secondaryTextColor,
            background = themeBackgroundColor,
            labelContainer = labelContainerColor,
        )
    }

fun ThemeSettings.hasBackgroundImage(isDark: Boolean): Boolean =
    if (isDark) !backgroundImageDark.isNullOrBlank() else !backgroundImageLight.isNullOrBlank()

/**
 * 容器背景九宫格的切分线，存的是四角角块占图宽/图高的比例（与 HighlightRule 的 np* 同语义）。
 * 渲染时换算成 NinePatchDrawHelper 需要的绝对线位置：左线 = left，右线 = 1 - right。
 */
data class ContainerNineSlice(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    val leftX: Float get() = left
    val rightX: Float get() = 1f - right
    val topY: Float get() = top
    val bottomY: Float get() = 1f - bottom
}

/** 解析 CSV "npL,npR,npT,npB"；格式不符返回 null（按未切分处理） */
fun parseContainerNineSlice(csv: String?): ContainerNineSlice? {
    val parts = csv?.split(',')?.map { it.trim() } ?: return null
    if (parts.size != 4) return null
    val values = parts.map { it.toFloatOrNull() ?: return null }
    return ContainerNineSlice(values[0], values[1], values[2], values[3])
        .takeIf {
            it.left in 0f..0.98f && it.right in 0f..0.98f &&
                it.top in 0f..0.98f && it.bottom in 0f..0.98f
        }
}

/** Float.toString 恒用小数点，不受设备 locale 影响，可直接 join */
fun ContainerNineSlice.toCsv(): String =
    listOf(left, right, top, bottom).joinToString(",")
