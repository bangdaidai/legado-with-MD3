package io.legado.app.ui.widget.components.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.text.AppText

/** 标签大小变体 */
enum class TagChipSize {
    /** 紧凑尺寸，适合书架等空间受限场景 */
    Small,
    /** 默认尺寸，适合信息页/详情页 */
    Medium,
}

/**
 * 统一标签芯片组件（书架 / 书籍信息 / 阅读记忆三处共用）。
 *
 * @param tag 标签名
 * @param color 标签颜色（可为 null，此时使用默认色）
 * @param size 标签大小
 * @param onClick 点击回调
 * @param onRemove 移除按钮回调（提供时会显示关闭图标）
 */
@Composable
fun TagChip(
    tag: String,
    color: Long? = null,
    size: TagChipSize = TagChipSize.Medium,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (horizontalPadding, verticalPadding) = if (size == TagChipSize.Small) {
        6.dp to 3.dp
    } else {
        10.dp to 5.dp
    }

    val tagColor = color?.let { Color(it) }
    val backgroundColor = if (tagColor != null) {
        tagColor.copy(alpha = 0.14f)
    } else {
        LegadoTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (tagColor != null) {
        tagColor
    } else {
        LegadoTheme.colorScheme.primary
    }

    val clickModifier = if (onClick != null || onRemove != null) {
        Modifier.clickable { (onClick ?: onRemove)?.invoke() }
    } else Modifier

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(backgroundColor)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .then(clickModifier),
    ) {
        AppText(
            text = tag,
            style = if (size == TagChipSize.Small) {
                LegadoTheme.typography.labelSmall
            } else {
                LegadoTheme.typography.labelMedium
            },
            color = contentColor,
        )
        if (onRemove != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除",
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove.invoke() },
                tint = contentColor,
            )
        }
    }
}