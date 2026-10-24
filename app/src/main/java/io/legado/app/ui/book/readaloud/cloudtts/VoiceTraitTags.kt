package io.legado.app.ui.book.readaloud.cloudtts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.card.NormalCard

/**
 * 音色卡片的标签体系（对齐 legado_NG 的音色卡）：
 * 性别 / 语言 / 风格 / 描述标签各自一块彩色圆角底，引擎名做灰字后缀，
 * 取代「女 · 温柔 · 活泼」式的纯文本拼接。
 */

/** 彩色圆角小标签；过长文本截断，避免一行标签把卡片撑爆。 */
@Composable
fun VoiceTraitTag(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    AppText(
        text = text,
        modifier = modifier
            .widthIn(max = 116.dp)
            .background(container, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = content,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun tagColors(): List<Pair<Color, Color>> = listOf(
    LegadoTheme.colorScheme.secondaryContainer to LegadoTheme.colorScheme.onSecondaryContainer,
    LegadoTheme.colorScheme.tertiaryContainer to LegadoTheme.colorScheme.onTertiaryContainer,
    LegadoTheme.colorScheme.primaryContainer to LegadoTheme.colorScheme.onPrimaryContainer,
    LegadoTheme.colorScheme.surfaceVariant to LegadoTheme.colorScheme.onSurfaceVariant,
)

/**
 * 标签行：性别（有才显示）+ 语言 + 风格 + 描述标签五色轮换 + 引擎名灰字。
 * [fallback] 为空标签体系时的兜底文本（保持旧行为）。
 */
@Composable
fun VoiceTraitTagRow(
    genderLabel: String?,
    languageLabel: String?,
    styleLabel: String?,
    tags: List<String>,
    engineName: String?,
    modifier: Modifier = Modifier,
) {
    val colors = tagColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var index = 0
        genderLabel?.let {
            VoiceTraitTag(
                text = it,
                container = colors[0].first,
                content = colors[0].second,
            )
            index = 1
        }
        languageLabel?.let {
            VoiceTraitTag(
                text = it,
                container = colors[2].first,
                content = colors[2].second,
            )
        }
        styleLabel?.let {
            VoiceTraitTag(
                text = it,
                container = colors[1].first,
                content = colors[1].second,
            )
        }
        tags.forEach { tag ->
            if (tag.isBlank()) return@forEach
            val color = colors[index % colors.size]
            VoiceTraitTag(text = tag, container = color.first, content = color.second)
            index++
        }
        engineName?.takeIf { it.isNotBlank() }?.let {
            AppText(
                text = it,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 音色卡片：标题行（含尾部操作槽）+ 标签行，视觉结构与 TinySettingItem 对齐
 * （NormalCard 12dp 圆角、surfaceContainerLow 底、水平 12dp 内边距）。
 */
@Composable
fun VoiceCardItem(
    title: String,
    modifier: Modifier = Modifier,
    tags: (@Composable () -> Unit)? = null,
    scrollableTags: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    NormalCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .padding(bottom = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        cornerRadius = 12.dp,
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = title,
                    style = LegadoTheme.typography.titleSmallEmphasized,
                    color = LegadoTheme.colorScheme.onSurface,
                )
                tags?.invoke()
                // 横向滑动区：内容超出宽度时左右滑看全，不与右侧按钮抢空间
                scrollableTags?.invoke()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                trailingContent?.invoke()
            }
        }
    }
}
