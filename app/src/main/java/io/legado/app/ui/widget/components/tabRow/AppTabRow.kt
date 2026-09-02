package io.legado.app.ui.widget.components.tabRow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
            //非默认 Tab 样式时，标签之间的间距（与 AppTab 中 endPadding 一致）
            val tabEndSpacing = if (!useDefaultTabPadding) 24.dp else 0.dp
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                divider = { },
                containerColor = Color.Transparent,
                minTabWidth = 0.dp,
                indicator = {
                    if (!useDefaultTabPadding) {
                        //自定义指示器：宽度仅匹配文字，排除标签末尾间距
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                            width = if (selectedTabIndex < tabTitles.lastIndex) {
                                TabRowDefaults.ScrollableTabRowMinTabWidth
                            } else {
                                androidx.compose.ui.unit.Dp.Unspecified
                            },
                            height = 3.dp,
                        )
                    } else {
                        //使用 Material 3 默认指示器
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex)
                        )
                    }
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
        val endPadding = if (showEndSpacing) 24.dp else 0.dp
        Box(
            modifier = Modifier
                .height(48.dp)
                .padding(end = endPadding)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AppText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LegadoTheme.typography.labelLargeEmphasized,
                color = if (selected) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}