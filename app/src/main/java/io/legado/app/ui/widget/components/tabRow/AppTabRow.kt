package io.legado.app.ui.widget.components.tabRow

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
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
        if (useDefaultTabPadding) {
            // 老逻辑：保持原样
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
                            showEndSpacing = false
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
                            showEndSpacing = false
                        )
                    }
                }
            }
        } else {
            // 新逻辑：精确对齐（方案 A）
            PreciseTabRow(
                tabTitles = tabTitles,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected,
                modifier = modifier,
                isScrollable = isScrollable
            )
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

@Composable
private fun PreciseTabRow(
    tabTitles: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isScrollable: Boolean
) {
    val density = LocalDensity.current
    var tabPositions by remember { mutableStateOf(List(tabTitles.size) { TabPosition(0f, 0f) }) }

    Column(modifier = modifier) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 16.dp,
            divider = { },
            indicator = { },
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabTitles.forEachIndexed { index, title ->
                if (index > 0) {
                    Spacer(modifier = Modifier.width(24.dp))
                }
                AppText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LegadoTheme.typography.labelLargeEmphasized,
                    color = if (index == selectedTabIndex)
                        LegadoTheme.colorScheme.primary
                    else
                        LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .clickable { onTabSelected(index) }
                        .onGloballyPositioned { coords ->
                            val posInParent = coords.positionInParent()
                            tabPositions = tabPositions.toMutableList().also {
                                it[index] = TabPosition(
                                    offset = posInParent.x,
                                    width = coords.size.width.toFloat()
                                )
                            }
                        }
                )
            }
        }

        val selected = tabPositions.getOrNull(selectedTabIndex)
        if (selected != null && selected.width > 0f) {
            val animOffset by animateFloatAsState(
                targetValue = selected.offset,
                animationSpec = tween(250)
            )
            val animWidth by animateFloatAsState(
                targetValue = selected.width,
                animationSpec = tween(250)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(animOffset.roundToInt(), 0) }
                        .width(with(density) { animWidth.toDp() })
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(LegadoTheme.colorScheme.primary)
                )
            }
        }
    }
}

private data class TabPosition(val offset: Float, val width: Float)