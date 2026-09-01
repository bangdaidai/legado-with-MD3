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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    checkedContentDescription: String = "已选择",
    uncheckedContentDescription: String = "未选择"
) {
    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        val verticalPad = if (compact) 6.dp else 8.dp
        val horizontalPad = if (compact) 8.dp else 12.dp
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
                },
            cornerRadius = cornerRadius ?: 12.dp,
            onClick = onToggle,
            containerColor = if (selected) {
                MiuixTheme.colorScheme.primaryContainer
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
                        tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                    )
                }

                MiuixText(
                    modifier = Modifier
                        .padding(vertical = verticalPad),
                    text = label,
                    style = LegadoTheme.typography.labelMediumEmphasized,
                    maxLines = 1,
                    softWrap = false,
                    color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface
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
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                )
                .then(borderModifier)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .semantics {
                    toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    stateDescription = if (selected) checkedContentDescription else uncheckedContentDescription
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onToggle,
            modifier = modifier,
            shape = cornerRadius?.let { RoundedCornerShape(it) } ?: FilterChipDefaults.shape,
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
