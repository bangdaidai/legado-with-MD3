package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StarColorActive = Color(0xFFFFB300)
private val StarColorInactive = Color(0xFF9AA0A6)

/**
 * 阅读记忆专用的可点击五星评分组件，带轻微的选中缩放动效。
 *
 * @param rating 当前评分（0..maxStars，支持半星）
 * @param onRatingChanged 点击星星回调，返回所选整数星级
 * @param enabled 是否可交互
 * @param maxStars 最大星数
 */
@Composable
fun ReadingMemoryRatingBar(
    rating: Float,
    onRatingChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxStars: Int = 5,
) {
    Row(modifier = modifier) {
        for (i in 1..maxStars) {
            val index = i.toFloat()
            val isFilled = rating >= index
            val isHalf = !isFilled && rating >= index - 0.5f
            val icon = when {
                isFilled -> Icons.Filled.Star
                isHalf -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Filled.StarOutline
            }
            val interactionSource = remember { MutableInteractionSource() }
            val scale by animateFloatAsState(
                targetValue = if (isFilled) 1.15f else 1f,
                label = "ratingStarScale",
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFilled || isHalf) StarColorActive else StarColorInactive,
                modifier = Modifier
                    .size(28.dp)
                    .scale(scale)
                    .then(
                        if (enabled) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { onRatingChanged(index) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ratingTint(isFilled: Boolean) = if (isFilled) StarColorActive else MaterialTheme.colorScheme.outline
