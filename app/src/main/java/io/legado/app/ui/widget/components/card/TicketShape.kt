package io.legado.app.ui.widget.components.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme

class TicketShape(
    private val notchRadius: Dp = 6.dp,
    private val cornerRadius: Dp = 8.dp,
    private val notchCenterY: Float? = null,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val notchPx = with(density) { notchRadius.toPx() }
        val cornerPx = with(density) { cornerRadius.toPx() }
        val midY = notchCenterY?.takeIf { it > 0f } ?: (size.height / 2f)

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
            // Left notch (semicircle opening inward/right)
            addOval(
                Rect(
                    center = Offset(0f, midY),
                    radius = notchPx,
                )
            )
            // Right notch (semicircle opening inward/left)
            addOval(
                Rect(
                    center = Offset(size.width, midY),
                    radius = notchPx,
                )
            )
        }

        val combined = Path.combine(PathOperation.Difference, roundedRect, notches)
        return Outline.Generic(combined)
    }
}

@Composable
fun TicketNotchDivider(
    modifier: Modifier = Modifier,
    notchRadius: Dp = 6.dp,
    color: Color = LegadoTheme.colorScheme.outlineVariant,
) {
    val density = LocalDensity.current
    val dashPx = with(density) { 4.dp.toPx() }
    val notchPx = with(density) { notchRadius.toPx() }
    val strokePx = with(density) { 1.dp.toPx() }

    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(notchPx, size.height / 2f),
            end = Offset(size.width - notchPx, size.height / 2f),
            strokeWidth = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx), 0f),
        )
    }
}
