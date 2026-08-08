package io.legado.app.ui.widget.components.bookmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.AppTextFieldSurface
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkEditSheet(
    show: Boolean,
    bookmark: Bookmark,
    onDismiss: () -> Unit,
    onSave: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onGenerateBookplate: ((Bookmark) -> Unit)? = null,
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var bookText by remember(bookmark) { mutableStateOf(bookmark.bookText) }
    var content by remember(bookmark) { mutableStateOf(bookmark.content) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = bookmark.chapterName,
        startAction = {
            MediumTonalButton(
                onClick = { showDeleteConfirmDialog = true },
                icon = AppIcons.Delete,
                contentDescription = stringResource(R.string.delete)
            )
        },
        endAction = {
            MediumTonalButton(
                onClick = {
                    // 必须用 copy 而非 apply：原地修改会让上游 StateFlow 的新旧状态相等而被去重，导致列表不刷新
                    onSave(bookmark.copy(bookText = bookText, content = content))
                },
                icon = AppIcons.Check,
                contentDescription = stringResource(R.string.action_save)
            )
        }
    ) {
        BookmarkEditContent(
            bookmark = bookmark,
            bookText = bookText,
            onBookTextChange = { bookText = it },
            content = content,
            onContentChange = { content = it },
        )
        if (onGenerateBookplate != null) {
            MediumTonalButton(
                onClick = {
                    onGenerateBookplate(bookmark.copy(bookText = bookText, content = content))
                },
                modifier = Modifier.fillMaxWidth(),
                text = "生成书摘票",
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    AppAlertDialog(
        show = showDeleteConfirmDialog,
        onDismissRequest = { showDeleteConfirmDialog = false },
        title = stringResource(R.string.confirm_delete_bookmark),
        text = stringResource(R.string.delete_bookmark_message),
        confirmText = stringResource(R.string.delete),
        onConfirm = {
            showDeleteConfirmDialog = false
            onDelete(bookmark)
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { showDeleteConfirmDialog = false }
    )
}

@Composable
fun BookmarkEditContent(
    bookmark: Bookmark,
    bookText: String,
    onBookTextChange: (String) -> Unit,
    content: String,
    onContentChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
    ) {
        AppTextFieldSurface(
            value = bookText,
            onValueChange = onBookTextChange,
            label = stringResource(R.string.bookmark_original_text),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 10
        )

        Spacer(modifier = Modifier.height(12.dp))

        AppTextFieldSurface(
            value = content,
            onValueChange = onContentChange,
            label = stringResource(R.string.bookmark_note),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
