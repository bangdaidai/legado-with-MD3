package io.legado.app.ui.widget.components.heatmap

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class HeatmapMode {
    COUNT, TIME
}

data class HeatmapConfig(
    val cellSize: Dp = 12.dp,
    val touchTargetSize: Dp = 14.dp,
    val cellSpacing: Dp = 3.dp,
    val cornerRadius: Dp = 3.dp,
    val gradientWidth: Dp = 12.dp,
    val legendSize: Dp = 10.dp
) {
    val interactiveCellSize: Dp
        get() = maxOf(cellSize, touchTargetSize)
}
