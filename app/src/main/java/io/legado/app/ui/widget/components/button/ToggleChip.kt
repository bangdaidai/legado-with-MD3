package io.legado.app.ui.widget.components.button

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LegadoTheme.composeEngine
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.card.NormalCard
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun ToggleChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
    compact: Boolean = false,
    light: Boolean = false,
    enabled: Boolean = true,
    checkedContentDescription: String = "已选择",
    uncheckedContentDescription: String = "未选择",
    textStyle: TextStyle? = null,
    horizontalPadding: Dp? = null
) {
    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        val verticalPad = if (compact) 6.dp else 8.dp
        val horizontalPad = horizontalPadding ?: if (compact) 8.dp else 12.dp
        val contentColor = MiuixTheme.colorScheme.onSurface
            .copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
        NormalCard (
            modifier = modifier
                .padding(vertical = if (compact) 0.dp else 2.dp)
                .semantics {
                    toggleableState = if (selected) {
                        ToggleableState.On
                    } else {
                        ToggleableState.Off
                    }
                    stateDescription = if (selected) {
                        checkedContentDescription
                    } else {
                        uncheckedContentDescription
                    }
                    if (!enabled) disabled()
                },
            cornerRadius = cornerRadius ?: 12.dp,
            // 不给 onClick 就是不可点：BaseCard 不再挂 combinedClickable，禁用态也不会有水波纹
            onClick = if (enabled) onToggle else null,
            containerColor = if (selected) {
                MiuixTheme.colorScheme.primaryContainer
            } else if (light) {
                Color.Transparent
            } else {
                MiuixTheme.colorScheme.surfaceContainer
            }
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = horizontalPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                AnimatedVisibility(
                    visible = selected && !compact
                ) {
                    MiuixIcon(
                        imageVector = Icons.Default.Check,
                        contentDescription = checkedContentDescription,
                        modifier = Modifier
                            .padding(end = if (compact) 4.dp else 8.dp)
                            .size(if (compact) 14.dp else 16.dp),
                        tint = (if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface)
                            .copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
                    )
                }

                MiuixText(
                    modifier = Modifier
                        .padding(vertical = verticalPad),
                    text = label,
                    style = textStyle ?: LegadoTheme.typography.labelMediumEmphasized,
                    maxLines = 1,
                    softWrap = false,
                    color = when {
                        selected -> MiuixTheme.colorScheme.onPrimaryContainer
                        light -> LegadoTheme.colorScheme.onSurfaceVariant
                        else -> contentColor
                    }
                )
            }
        }
    } else if (compact) {
        val themeSettings = LocalAppUiConfiguration.current.theme
        val resolvedShape = if (themeSettings.overrideBaseCardCornerRadius) {
            RoundedCornerShape(themeSettings.baseCardCornerRadius.dp)
        } else {
            cornerRadius?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(50)
        }
        val borderModifier = if (themeSettings.overrideBaseCardBorder) {
            val configuredColor = if (LegadoTheme.isDark) {
                themeSettings.baseCardBorderColorNight
            } else {
                themeSettings.baseCardBorderColor
            }
            val borderColor = configuredColor.takeIf { it != 0 }?.let(::Color)
                ?: LegadoTheme.colorScheme.outlineVariant
            Modifier.border(
                BorderStroke(themeSettings.baseCardBorderWidth.dp, borderColor),
                resolvedShape
            )
        } else {
            Modifier
        }
        Row(
            modifier = modifier
                .clip(resolvedShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        light -> Color.Transparent
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }
                )
                .then(borderModifier)
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = horizontalPadding ?: 6.dp, vertical = 4.dp)
                .semantics {
                    toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    stateDescription = if (selected) checkedContentDescription else uncheckedContentDescription
                    if (!enabled) disabled()
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = textStyle ?: MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    light -> LegadoTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onToggle,
            modifier = modifier,
            shape = cornerRadius?.let { RoundedCornerShape(it) } ?: FilterChipDefaults.shape,
            enabled = enabled,
            label = { Text(label) },
            leadingIcon = if (selected) {
                {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = checkedContentDescription,
                        Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null
        )
    }
}

/** 禁用态内容透明度：与系列按钮（SeriesButton）的 disabledContentColor 同一口径。 */
private const val DISABLED_ALPHA = 0.38f
