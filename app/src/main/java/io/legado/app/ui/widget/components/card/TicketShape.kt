package io.legado.app.ui.widget.components.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme

class TicketShape(
    private val notchRadius: Dp = 6.dp,
    private val cornerRadius: Dp = 8.dp,
    private val notchCenterYs: List<Float> = emptyList(),
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val notchPx = with(density) { notchRadius.toPx() }
        val cornerPx = with(density) { cornerRadius.toPx() }
        val centers = notchCenterYs.filter { it > 0f && it < size.height }
            .ifEmpty { listOf(size.height / 2f) }

        val roundedRect = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = cornerPx,
                    radiusY = cornerPx,
                )
            )
        }

        val notches = Path().apply {
            centers.forEach { y ->
                // Left notch (semicircle opening inward/right)
                addOval(Rect(center = Offset(0f, y), radius = notchPx))
                // Right notch (semicircle opening inward/left)
                addOval(Rect(center = Offset(size.width, y), radius = notchPx))
            }
        }

        val combined = Path.combine(PathOperation.Difference, roundedRect, notches)
        return Outline.Generic(combined)
    }
}

/**
 * 让票据容器内任意层级的 [TicketNotchDivider] 自行上报纵向位置,
 * 容器据此在卡片左右边缘对应高度挖出齿孔。
 */
@Stable
class TicketNotchRegistry internal constructor(
    private val positions: MutableMap<Any, Float>,
    private val container: () -> LayoutCoordinates?,
) {
    fun update(key: Any, coordinates: LayoutCoordinates) {
        val containerCoordinates = container() ?: return
        if (!containerCoordinates.isAttached || !coordinates.isAttached) return
        val y = containerCoordinates.localPositionOf(coordinates, Offset.Zero).y +
            coordinates.size.height / 2f
        if (positions[key] != y) positions[key] = y
    }

    fun remove(key: Any) {
        positions.remove(key)
    }
}

val LocalTicketNotchRegistry = compositionLocalOf<TicketNotchRegistry?> { null }

@Composable
fun rememberTicketNotchRegistry(
    positions: MutableMap<Any, Float>,
    container: () -> LayoutCoordinates?,
): TicketNotchRegistry = remember(positions) { TicketNotchRegistry(positions, container) }

@Composable
fun TicketNotchDivider(
    modifier: Modifier = Modifier,
    notchRadius: Dp = 6.dp,
    color: Color = LegadoTheme.colorScheme.outlineVariant,
    strokeWidth: Dp = 1.dp,
    dotted: Boolean = false,
) {
    val density = LocalDensity.current
    val notchPx = with(density) { notchRadius.toPx() }
    val effectiveStroke: Dp = if (dotted) {
        val scaled = strokeWidth * 1.5f
        if (scaled >= 1.5.dp) scaled else 1.5.dp
    } else {
        strokeWidth
    }
    val effectiveStrokePx = with(density) { effectiveStroke.toPx() }
    val dashPx = with(density) { 4.dp.toPx() }

    val registry = LocalTicketNotchRegistry.current
    val key = remember { Any() }
    DisposableEffect(registry, key) {
        onDispose { registry?.remove(key) }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(effectiveStroke)
            .then(
                if (registry != null) {
                    Modifier.onGloballyPositioned { registry.update(key, it) }
                } else {
                    Modifier
                }
            )
    ) {
        drawLine(
            color = color,
            start = Offset(notchPx, size.height / 2f),
            end = Offset(size.width - notchPx, size.height / 2f),
            strokeWidth = effectiveStrokePx,
            cap = if (dotted) StrokeCap.Round else StrokeCap.Butt,
            pathEffect = if (dotted) {
                PathEffect.dashPathEffect(floatArrayOf(0f, effectiveStrokePx + dashPx), 0f)
            } else {
                PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx), 0f)
            },
        )
    }
}
