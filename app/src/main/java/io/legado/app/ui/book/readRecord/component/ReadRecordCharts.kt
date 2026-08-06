package io.legado.app.ui.book.readRecord.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.readRecord.ReadRecordFormatter
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import kotlin.math.roundToInt

/**
 * 柱状图的单根柱子。[label] 为 null 时该柱不显示 X 轴刻度文字。
 */
@Stable
data class ReadingBar(
    val label: String?,
    val value: Long
)

@Composable
fun ReadingTimeBarChartCard(
    bars: List<ReadingBar>,
    title: String,
    barWidthFraction: Float = 0.6f,
    modifier: Modifier = Modifier
) {
    val rawMaxTime = bars.maxOfOrNull { it.value }?.coerceAtLeast(1L) ?: 1L

    // 向上取整逻辑：根据时长跨度选择合适的对齐单位
    val roundedMaxTime = when {
        rawMaxTime < 60_000 -> 60_000L // 不足1分钟取1分钟
        rawMaxTime < 10 * 60_000 -> ((rawMaxTime + 59_999) / 60_000) * 60_000L // 10分钟内按1分钟对齐
        rawMaxTime < 60 * 60_000 -> ((rawMaxTime + 5 * 60_000 - 1) / (5 * 60_000)) * 5 * 60_000L // 1小时内按5分钟对齐
        rawMaxTime < 12 * 3600_000 -> ((rawMaxTime + 3600_000 - 1) / 3600_000) * 3600_000L // 12小时内按1小时对齐
        else -> ((rawMaxTime + 4 * 3600_000 - 1) / (4 * 3600_000)) * 4 * 3600_000L // 超过12小时按4小时对齐
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppText(title, style = LegadoTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                // Chart
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val maxValue = bars.maxOfOrNull { it.value }
                    val maxIndex = bars.indexOfFirst { it.value == maxValue }
                    bars.forEachIndexed { index, bar ->
                        val targetHeightFactor = bar.value.toFloat() / roundedMaxTime
                        val heightFactor by animateFloatAsState(
                            targetValue = targetHeightFactor,
                            animationSpec = tween(durationMillis = 320, delayMillis = index * 20),
                            label = "BarHeight"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 16.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                if (index == maxIndex && bar.value > 0) {
                                    val barHeightPx =
                                        constraints.maxHeight.toFloat() * heightFactor.coerceAtLeast(0.01f)
                                    AppText(
                                        text = ReadRecordFormatter.formatBarPeakDuration(bar.value),
                                        style = LegadoTheme.typography.labelSmall,
                                        fontSize = 8.sp,
                                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            // 数值标签放在柱顶上方，避免与高柱重叠
                                            .offset {
                                                IntOffset(0, -(barHeightPx + 2.dp.toPx()).roundToInt())
                                            }
                                            .wrapContentWidth(unbounded = true)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(barWidthFraction)
                                        .fillMaxHeight(heightFactor.coerceAtLeast(0.01f))
                                        .padding(horizontal = 1.dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(
                                            if (bar.value > 0) LegadoTheme.colorScheme.primary
                                            else LegadoTheme.colorScheme.surfaceVariant
                                        )
                                )
                            }

                            Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.TopCenter) {
                                bar.label?.let { label ->
                                    AppText(
                                        text = label,
                                        style = LegadoTheme.typography.labelSmall,
                                        fontSize = 8.sp,
                                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible,
                                        modifier = Modifier.wrapContentWidth(unbounded = true)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
