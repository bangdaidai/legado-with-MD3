package io.legado.app.ui.widget.components.tabRow

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.text.AppText
import top.yukonga.miuix.kmp.basic.TabRowDefaults as MiuixTabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour

@Composable
fun AppTabRow(
    tabTitles: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isScrollable: Boolean = true,
    useDefaultTabPadding: Boolean = true
) {
    val composeEngine = LegadoTheme.composeEngine

    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        TabRowWithContour(
            tabs = tabTitles,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = modifier
                .padding(vertical = 4.dp),
            colors = MiuixTabRowDefaults.tabRowColors(
                backgroundColor = Color.Transparent
            )
        )
    } else {
        if (isScrollable) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                divider = { },
                containerColor = Color.Transparent,
                minTabWidth = 0.dp,
                indicator = {
                    //自定义指示器：宽度匹配文字内容，对齐文字
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true)
                    )
                },
                modifier = modifier
            ) {
                tabTitles.forEachIndexed { index, title ->
                    AppTab(
                        selected = selectedTabIndex == index,
                        onClick = { onTabSelected(index) },
                        title = title,
                        useDefaultTabPadding = useDefaultTabPadding,
                        showEndSpacing = !useDefaultTabPadding && index < tabTitles.lastIndex
                    )
                }
            }
        } else {
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                divider = { },
                containerColor = Color.Transparent,
                modifier = modifier
            ) {
                tabTitles.forEachIndexed { index, title ->
                    AppTab(
                        selected = selectedTabIndex == index,
                        onClick = { onTabSelected(index) },
                        title = title,
                        useDefaultTabPadding = useDefaultTabPadding,
                        showEndSpacing = !useDefaultTabPadding && index < tabTitles.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTab(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    useDefaultTabPadding: Boolean = true,
    showEndSpacing: Boolean = false
) {
    if (useDefaultTabPadding) {
        Tab(
            selected = selected,
            onClick = onClick,
            text = {
                AppText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LegadoTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = if (selected) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    } else {
        // 对称 padding 提供间距，matchContentSize 自动对齐 indicator 与文字
        // 旧方案用 modifier.padding(end=24.dp) 导致 indicator 在 Tab 内居中偏移
        // 对称 padding 让 Tab 中心 = 内容中心，indicator 和文字都居中对齐
        val horizontalPadding = if (showEndSpacing) 12.dp else 0.dp
        Tab(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            text = {
                AppText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LegadoTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = if (selected) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}