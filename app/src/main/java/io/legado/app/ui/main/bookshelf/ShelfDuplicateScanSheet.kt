package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.ShelfDuplicateCopy
import io.legado.app.domain.model.ShelfDuplicateGroup
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.divider.PillDivider
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toTimeAgo
import kotlinx.collections.immutable.ImmutableSet

/**
 * 书架同名书籍检测。
 *
 * 面板把「哪部作品有几个副本」摆出来并给出去留判断所需的对比信息（书源、进度、最近阅读），
 * 每个副本可以直接删除（带确认，本地导入的原文件保留）；跨副本合并进度仍走书籍详情页的
 * 换源流程，那条路径才会把阅读进度、标签和阅读记忆一起迁到新 bookUrl。
 */
@Composable
fun ShelfDuplicateScanSheet(
    show: Boolean,
    state: ShelfDuplicateScanUiState,
    onScan: () -> Unit,
    onDelete: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // 待确认删除的副本 bookUrl：面板私有 UI 状态，关面板（不组合）即丢弃
    var pendingDeleteUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(show) {
        if (show) onScan()
    }

    val pendingDelete = pendingDeleteUrl?.let { url ->
        state.result.groups.firstNotNullOfOrNull { group ->
            group.copies.firstOrNull { it.bookUrl == url }?.let { group to it }
        }
    }

    if (pendingDelete != null) {
        val (group, copy) = pendingDelete
        val progressLabel = stringResource(
            R.string.bookshelf_duplicate_scan_progress,
            copy.progressText(),
        )
        AppAlertDialog(
            show = true,
            onDismissRequest = { pendingDeleteUrl = null },
            title = stringResource(R.string.bookshelf_duplicate_delete_title),
            text = stringResource(
                R.string.bookshelf_duplicate_delete_text,
                copy.originName.ifBlank {
                    stringResource(R.string.bookshelf_duplicate_scan_unknown_source)
                },
                group.name,
                progressLabel,
            ),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                pendingDeleteUrl = null
                onDelete(copy.bookUrl)
            },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { pendingDeleteUrl = null },
        )
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.bookshelf_duplicate_scan),
        endAction = {
            MediumTonalButton(
                onClick = onScan,
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.retry),
                enabled = !state.isScanning,
            )
        },
    ) {
        when {
            // 失败不是"没有结果"，用纯文案而不是 EmptyMessage —— 后者的随机颜文字
            // 放在错误提示上读起来像在开玩笑
            state.failed -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppText(
                    text = stringResource(R.string.bookshelf_duplicate_scan_failed),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.error,
                )
            }

            state.isScanning -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppCircularProgressIndicator()
            }

            state.result.groups.isEmpty() -> EmptyMessage(
                message = stringResource(
                    R.string.bookshelf_duplicate_scan_none,
                    state.result.scannedBookCount,
                ),
            )

            else -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(
                    text = stringResource(
                        R.string.bookshelf_duplicate_scan_result,
                        state.result.scannedBookCount,
                        state.result.duplicateGroupCount,
                        state.result.duplicateCopyCount,
                    ),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                AppText(
                    text = stringResource(R.string.bookshelf_duplicate_scan_hint),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.result.groups, key = { "${it.name}|${it.author}" }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            deletingBookUrls = state.deletingBookUrls,
                            onDelete = { pendingDeleteUrl = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: ShelfDuplicateGroup,
    deletingBookUrls: ImmutableSet<String>,
    onDelete: (String) -> Unit,
) {
    GlassCard(containerColor = LegadoTheme.colorScheme.onSheetContent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = group.name,
                    style = LegadoTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TextCard(
                    text = stringResource(R.string.bookshelf_duplicate_scan_copies, group.copyCount),
                    cornerRadius = 4.dp,
                    horizontalPadding = 4.dp,
                    verticalPadding = 0.dp,
                    backgroundColor = LegadoTheme.colorScheme.surfaceVariant,
                    contentColor = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (group.author.isNotBlank()) {
                AppText(
                    text = group.author,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PillDivider()
            group.copies.forEach { copy ->
                DuplicateCopyRow(
                    copy = copy,
                    deleting = copy.bookUrl in deletingBookUrls,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun DuplicateCopyRow(
    copy: ShelfDuplicateCopy,
    deleting: Boolean,
    onDelete: (String) -> Unit,
) {
    // stringResource 先取出来再拼：放进 buildString / ifBlank 的 lambda 里虽然编译器允许
    // （inline lambda 继承 composable 上下文），但读起来像在调用非 composable 函数
    val progressLabel = stringResource(
        R.string.bookshelf_duplicate_scan_progress,
        copy.progressText(),
    )
    val unknownSource = stringResource(R.string.bookshelf_duplicate_scan_unknown_source)
    val localLabel = stringResource(R.string.local)
    val subtitle = buildString {
        append(progressLabel)
        append(" · ")
        append(copy.durChapterTime.toTimeAgo())
        if (copy.isLocal) {
            append(" · ")
            append(localLabel)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoilBookCover(
            name = null,
            author = null,
            path = copy.coverUrl,
            bookUrl = copy.bookUrl,
            modifier = Modifier.size(width = 32.dp, height = 44.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = copy.originName.ifBlank { unknownSource },
                style = LegadoTheme.typography.labelMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = subtitle,
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SmallPlainButton(
            onClick = { onDelete(copy.bookUrl) },
            icon = Icons.Default.Delete,
            contentDescription = stringResource(R.string.delete),
            enabled = !deleting,
        )
    }
}