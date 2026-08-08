package io.legado.app.ui.widget.components.divider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LocalAppUiConfiguration

/**
 * 主题设置中的「分割线颜色」，未自定义时回退到默认灰。
 * 与 [SettingItemDivider] 的开关无关，只取颜色。
 */
@Composable
fun themeDividerColor(): Color {
    val themeSettings = LocalAppUiConfiguration.current.theme
    return if (themeSettings.itemDividerColor != 0) {
        Color(themeSettings.itemDividerColor)
    } else {
        Color.Gray.copy(alpha = 0.3f)
    }
}

@Composable
fun SettingItemDivider(
    modifier: Modifier = Modifier
) {
    val themeSettings = LocalAppUiConfiguration.current.theme
    if (!themeSettings.enableItemDivider) return

    val thickness = themeSettings.itemDividerWidth.dp
    val lengthPercent = themeSettings.itemDividerLength / 100f
    val dividerColor = themeDividerColor()

    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(lengthPercent)
                .height(thickness)
                .clip(CircleShape)
                .background(dividerColor)
        )
    }
}
