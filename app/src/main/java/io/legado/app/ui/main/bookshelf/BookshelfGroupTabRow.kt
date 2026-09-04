package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.text.AppText
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour

/**
 * 书架分组标签行。
 *
 * M3 引擎的布局契约：
 * - 滚动视口左内缩 16dp，内容在视口内被裁切，滑动中标签永远到不了屏幕左边缘；
 * - 尾部没有按钮时右内缩 16dp；有按钮时右侧不留边距，视口直接顶到按钮左侧
 *   （按钮自身负责与屏幕边缘保持距离）；
 * - 标签之间间距 24dp，标签盒子宽度严格等于文字宽度；
 * - 指示器放在"文字宽度"的列内 fillMaxWidth，因此宽度与文字完全一致、
 *   且只随文字居中对齐，不受视口边距和标签间距影响。
 *
 * Miuix 引擎保持 TabRowWithContour 原视觉，水平边距按规范取 12dp。
 */
@Composable
internal fun BookshelfGroupTabRow(
    tabTitles: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hasTrailingButton: Boolean = false,
) {
    val composeEngine = LegadoTheme.composeEngine

    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        TabRowWithContour(
            tabs = tabTitles,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = modifier.padding(
                start = 12.dp,
                end = if (hasTrailingButton) 0.dp else 12.dp,
                top = 4.dp,
                bottom = 4.dp
            ),
            colors = TabRowDefaults.tabRowColors(
                backgroundColor = Color.Transparent
            )
        )
    } else {
        val listState = rememberLazyListState()

        // 翻页或点选导致选中项变化时，把选中标签滚回可视区
        LaunchedEffect(selectedTabIndex, tabTitles.size) {
            if (tabTitles.isEmpty()) return@LaunchedEffect
            val target = selectedTabIndex.coerceIn(0, tabTitles.lastIndex)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            val fullyVisible = item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
            if (!fullyVisible) {
                listState.animateScrollToItem(target)
            }
        }

        LazyRow(
            state = listState,
            // 先内缩再裁切：裁切边界即内缩后的视口，滑动中标签内容在视口边缘被切掉。
            // 右侧有分组按钮时视口直接顶到按钮左侧，没有按钮时右内缩 16dp
            modifier = modifier
                .padding(
                    start = 16.dp,
                    end = if (hasTrailingButton) 0.dp else 16.dp
                )
                .clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(tabTitles) { index, title ->
                GroupTabItem(
                    title = title,
                    selected = index == selectedTabIndex,
                    onClick = { onTabSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun GroupTabItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 内层列以文字的 intrinsic 宽度为列宽，指示器在列内 fillMaxWidth：
        // 宽度 = 文字宽度，且只与文字居中对齐
        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppText(
                text = title,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = LegadoTheme.typography.labelLargeEmphasized,
                color = if (selected) {
                    LegadoTheme.colorScheme.primary
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) LegadoTheme.colorScheme.primary else Color.Transparent),
            )
        }
    }
}
