package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuLazy
import io.legado.app.ui.widget.components.text.AppText

/** 选项多于这个数换 [RoundDropdownMenuLazy]（音色表这类几十条的候选要能滚） */
private const val LAZY_MENU_THRESHOLD = 12

/**
 * 弹窗里的选项卡片：上标签下当前值，点卡片弹出圆角菜单选择——**纯选择，没有输入框**。
 *
 * 从人物详情弹窗的那张下拉卡提升而来：配音编辑这类表单要用同一种下拉（此前那里是
 * 可输入筛选的 [CastFieldStack]，点开候选时键盘会跟着弹出挡住选项），两处共用一份实现。
 */
@Composable
fun <T> OptionDropdownCard(
    label: String,
    text: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onOptionClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** 选项是否为当前值：菜单里高亮，用户不用靠背当前选的是什么 */
    isSelected: (T) -> Boolean = { false },
) {
    var showDropdown by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        NormalCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDropdown = true },
            cornerRadius = 12.dp,
            containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    AppText(
                        text = label,
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                    AppText(
                        text = text,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurface,
                    )
                }
                AppIcon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val pick: (T) -> Unit = { option ->
            onOptionClick(option)
            showDropdown = false
        }
        if (options.size < LAZY_MENU_THRESHOLD) {
            RoundDropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false },
            ) {
                options.forEach { option ->
                    RoundDropdownMenuItem(
                        text = optionLabel(option),
                        isSelected = isSelected(option),
                        onClick = { pick(option) },
                    )
                }
            }
        } else {
            OptInLazyMenu(
                expanded = showDropdown,
                onDismiss = { showDropdown = false },
                options = options,
                optionLabel = optionLabel,
                isSelected = isSelected,
                onPick = pick,
            )
        }
    }
}

/** 长候选的滚动菜单单独一层，只为收拢 [ExperimentalMaterial3ExpressiveApi] 的 OptIn。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> OptInLazyMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
) {
    RoundDropdownMenuLazy(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        options.forEachIndexed { index, option ->
            item(key = index) {
                RoundDropdownMenuItem(
                    text = optionLabel(option),
                    isSelected = isSelected(option),
                    onClick = { onPick(option) },
                )
            }
        }
    }
}
