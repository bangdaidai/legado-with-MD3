package io.legado.app.ui.book.readingmemory

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt
import io.legado.app.constant.ReadingStatus
import io.legado.app.data.entities.BookProtagonist
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.readRecord.component.StatItem
import io.legado.app.ui.book.readRecord.component.StatsGridCard
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.AppIconButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.utils.formatReadDuration
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReadingMemoryDetailRoute(
    bookUrl: String,
    onBack: () -> Unit,
) {
    val viewModel: ReadingMemoryDetailViewModel = koinViewModel(parameters = { parametersOf(bookUrl) })
    val uiState by viewModel.uiState.collectAsState(initial = ReadingMemoryDetailUiState())
    val protagonists by viewModel.protagonists.collectAsState(initial = emptyList())
    val reviews by viewModel.reviews.collectAsState(initial = emptyList())
    val excerpts by viewModel.excerpts.collectAsState(initial = emptyList())
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    ReadingMemoryDetailScreen(
        uiState = uiState,
        protagonists = protagonists,
        reviews = reviews,
        excerpts = excerpts,
        onBack = onBack,
        onStatusSelected = viewModel::setReadingStatus,
        onRatingSelected = viewModel::setRating,
        onAddTag = viewModel::addTag,
        onRemoveTag = viewModel::removeTag,
        onAddProtagonist = viewModel::addProtagonist,
        onRemoveProtagonist = viewModel::removeProtagonist,
        onExtractProtagonists = viewModel::extractProtagonists,
        onAddReview = viewModel::addReview,
        onUpdateReview = viewModel::updateReview,
        onDeleteReview = viewModel::deleteReview,
        onEditBook = {
            context.startActivity(
                Intent(context, BookInfoActivity::class.java).apply {
                    putExtra("bookUrl", bookUrl)
                },
            )
        },
        onShareBookplate = { shareBookplate(context, uiState) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    uiState: ReadingMemoryDetailUiState,
    protagonists: List<BookProtagonist>,
    reviews: List<BookReview>,
    excerpts: List<Bookmark>,
    onBack: () -> Unit,
    onStatusSelected: (ReadingStatus) -> Unit,
    onRatingSelected: (Float) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (BookTag) -> Unit,
    onAddProtagonist: (String) -> Unit,
    onRemoveProtagonist: (Long) -> Unit,
    onExtractProtagonists: () -> Unit,
    onAddReview: (String) -> Unit,
    onUpdateReview: (BookReview, String) -> Unit,
    onDeleteReview: (Long) -> Unit,
    onEditBook: () -> Unit,
    onShareBookplate: () -> Unit,
) {
    val book = uiState.book
    val memory = uiState.memory
    val status = ReadingStatus.fromValue(memory?.readingStatus ?: 0)
    val rating = memory?.rating ?: 0f
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    var statusDialogOpen by remember { mutableStateOf(false) }
    var tagDialogOpen by remember { mutableStateOf(false) }
    var protagonistDialogOpen by remember { mutableStateOf(false) }
    var reviewDialogOpen by remember { mutableStateOf(false) }
    var reviewDialogEdit by remember { mutableStateOf<BookReview?>(null) }
    var tagToRemove by remember { mutableStateOf<BookTag?>(null) }
    var protagonistToRemove by remember { mutableStateOf<BookProtagonist?>(null) }
    var reviewToDelete by remember { mutableStateOf<BookReview?>(null) }

    val tagState = rememberTextFieldState()
    val protagonistState = rememberTextFieldState()
    val reviewState = rememberTextFieldState()

    LaunchedEffect(tagDialogOpen) {
        if (tagDialogOpen) tagState.edit { replace(0, tagState.text.length, "") }
    }
    LaunchedEffect(protagonistDialogOpen) {
        if (protagonistDialogOpen) protagonistState.edit { replace(0, protagonistState.text.length, "") }
    }
    LaunchedEffect(reviewDialogOpen, reviewDialogEdit) {
        if (reviewDialogOpen) {
            reviewState.edit { replace(0, reviewState.text.length, reviewDialogEdit?.reviewContent ?: "") }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = {
                    AppText(
                        text = book?.name ?: stringResource(R.string.rm_reading_memory),
                        style = LegadoTheme.typography.titleLarge,
                    )
                },
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
                actions = {
                    AppIconButton(onClick = onEditBook) {
                        AppIcon(Icons.Default.Edit, contentDescription = null, tint = LegadoTheme.colorScheme.onSurface)
                    }
                    AppIconButton(onClick = onShareBookplate) {
                        AppIcon(Icons.Default.Share, contentDescription = null, tint = LegadoTheme.colorScheme.onSurface)
                    }
                },
            )
        },
    ) { padding ->
        if (book == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                AppText(stringResource(R.string.rm_loading))
            }
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item {
                ReadingMemoryHeader(
                    book = book,
                    status = status,
                    rating = rating,
                    onStatusClick = { statusDialogOpen = true },
                    onRatingSelected = onRatingSelected,
                )
            }
            item {
                StatsGridCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveHorizontalPadding(vertical = 8.dp),
                    title = stringResource(R.string.reading_data),
                    items = buildStats(uiState, reviews, excerpts),
                )
            }
            item { SessionSection(uiState.sessionsByMonth) }
            item { ExcerptSection(excerpts) }
            item {
                ReviewSection(
                    reviews = reviews,
                    onAdd = {
                        reviewDialogEdit = null
                        reviewDialogOpen = true
                    },
                    onEdit = {
                        reviewDialogEdit = it
                        reviewDialogOpen = true
                    },
                    onDelete = { reviewToDelete = it },
                )
            }
            item {
                TagSection(
                    tags = uiState.tags,
                    onAdd = { tagDialogOpen = true },
                    onRemove = { tagToRemove = it },
                )
            }
            item {
                ProtagonistSection(
                    protagonists = protagonists,
                    onAdd = { protagonistDialogOpen = true },
                    onExtract = onExtractProtagonists,
                    onRemove = { protagonistToRemove = it },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // 阅读状态选择
    if (statusDialogOpen) {
        AppAlertDialog(
            show = true,
            onDismissRequest = { statusDialogOpen = false },
            title = stringResource(R.string.rm_status_choose),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingStatus.entries.forEach { s ->
                        TextCard(
                            text = s.displayName,
                            onClick = {
                                onStatusSelected(s)
                                statusDialogOpen = false
                            },
                        )
                    }
                }
            },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { statusDialogOpen = false },
        )
    }

    // 添加标签
    AppAlertDialog(
        show = tagDialogOpen,
        onDismissRequest = { tagDialogOpen = false },
        title = stringResource(R.string.rm_add_tag),
        content = {
            AppTextField(
                state = tagState,
                label = { AppText(stringResource(R.string.rm_input_tag_hint)) },
            )
        },
        confirmText = stringResource(R.string.rm_add_tag),
        onConfirm = {
            val text = tagState.text.toString().trim()
            if (text.isNotEmpty()) onAddTag(text)
            tagDialogOpen = false
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { tagDialogOpen = false },
    )

    // 添加主角
    AppAlertDialog(
        show = protagonistDialogOpen,
        onDismissRequest = { protagonistDialogOpen = false },
        title = stringResource(R.string.rm_add_protagonist),
        content = {
            AppTextField(
                state = protagonistState,
                label = { AppText(stringResource(R.string.rm_input_protagonist_hint)) },
            )
        },
        confirmText = stringResource(R.string.rm_add_protagonist),
        onConfirm = {
            val text = protagonistState.text.toString().trim()
            if (text.isNotEmpty()) onAddProtagonist(text)
            protagonistDialogOpen = false
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { protagonistDialogOpen = false },
    )

    // 书评编辑
    AppAlertDialog(
        show = reviewDialogOpen,
        onDismissRequest = { reviewDialogOpen = false },
        title = stringResource(if (reviewDialogEdit == null) R.string.rm_add_review else R.string.rm_edit_review),
        content = {
            AppTextField(
                state = reviewState,
                label = { AppText(stringResource(R.string.rm_input_review_hint)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
            )
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = {
            val text = reviewState.text.toString().trim()
            if (text.isNotEmpty()) {
                if (reviewDialogEdit == null) onAddReview(text)
                else onUpdateReview(reviewDialogEdit!!, text)
            }
            reviewDialogOpen = false
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { reviewDialogOpen = false },
    )

    // 移除确认
    AppAlertDialog(
        data = tagToRemove,
        onDismissRequest = { tagToRemove = null },
        title = stringResource(R.string.rm_confirm_remove),
        textProvider = { name },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onRemoveTag(it) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { tagToRemove = null },
    )
    AppAlertDialog(
        data = protagonistToRemove,
        onDismissRequest = { protagonistToRemove = null },
        title = stringResource(R.string.rm_confirm_remove),
        textProvider = { name },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onRemoveProtagonist(it.id) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { protagonistToRemove = null },
    )
    AppAlertDialog(
        data = reviewToDelete,
        onDismissRequest = { reviewToDelete = null },
        title = stringResource(R.string.rm_confirm_remove),
        textProvider = { reviewContent },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onDeleteReview(it.id) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { reviewToDelete = null },
    )
}

@Composable
private fun ReadingMemoryHeader(
    book: io.legado.app.data.entities.Book,
    status: ReadingStatus,
    rating: Float,
    onStatusClick: () -> Unit,
    onRatingSelected: (Float) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CoilBookCover(
                    name = book.name,
                    author = book.author,
                    path = book.getDisplayCover(),
                    modifier = Modifier
                        .size(width = 96.dp, height = 132.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppText(
                        text = book.name,
                        style = LegadoTheme.typography.titleLarge,
                        color = LegadoTheme.colorScheme.onSurface,
                    )
                    AppText(
                        text = book.author,
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                    TextCard(text = status.displayName, onClick = onStatusClick)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (i in 0..4) {
                            val filled = i < rating.roundToInt()
                            AppIconButton(onClick = { onRatingSelected((i + 1).toFloat()) }) {
                                AppIcon(
                                    contentDescription = null,
                                    imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                                    tint = LegadoTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val progress = if (book.totalChapterNum > 0) {
                (book.durChapterIndex.toFloat() / book.totalChapterNum).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = LegadoTheme.colorScheme.primary,
                trackColor = LegadoTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            AppText(
                text = if (book.totalChapterNum > 0) {
                    stringResource(R.string.rm_read_progress_chapters, book.durChapterIndex, book.totalChapterNum)
                } else {
                    stringResource(R.string.rm_progress_unknown)
                },
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            val intro = book.getDisplayIntro()
            if (intro.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                IntroBlock(intro)
            }
        }
    }
}

@Composable
private fun IntroBlock(intro: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        AppText(
            text = intro,
            style = LegadoTheme.typography.bodyMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (intro.length > 80) {
            TextCard(
                text = stringResource(if (expanded) R.string.rm_collapse else R.string.rm_expand),
                onClick = { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            contentDescription = null,
            imageVector = icon,
            tint = LegadoTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        AppText(
            text = title,
            style = LegadoTheme.typography.titleMedium,
            color = LegadoTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun buildStats(
    uiState: ReadingMemoryDetailUiState,
    reviews: List<BookReview>,
    excerpts: List<Bookmark>,
): List<StatItem> {
    val s = uiState.stats
    val longest = buildString {
        append(formatReadDuration(s.maxDayReadTime))
        if (s.maxDayReadDate > 0) append(" (${formatDate(s.maxDayReadDate)})")
    }
    return listOf(
        StatItem(stringResource(R.string.reading_time), formatReadDuration(s.totalReadTime)),
        StatItem(stringResource(R.string.rm_reading_days), s.readingDays.toString()),
        StatItem(stringResource(R.string.rm_reading_progress), "${s.progressPercent}%"),
        StatItem(stringResource(R.string.rm_read_chapters), "${s.readChapterIndex}/${s.totalChapterNum}"),
        StatItem(stringResource(R.string.rm_longest_single_day), longest),
        StatItem(
            stringResource(R.string.rm_total_words_read),
            s.totalReadWordsWan?.let { String.format(Locale.CHINA, "%.1f万字", it) } ?: "-",
        ),
        StatItem(
            stringResource(R.string.rm_remaining_words),
            s.remainingWordsWan?.let { String.format(Locale.CHINA, "%.1f万字", it) } ?: "-",
        ),
        StatItem(stringResource(R.string.rm_total_words), s.wordCountText.ifEmpty { "-" }),
        StatItem(stringResource(R.string.rm_book_status), s.kindText.ifEmpty { "-" }),
        StatItem(stringResource(R.string.rm_last_read), s.lastReadText.ifEmpty { "-" }),
        StatItem(stringResource(R.string.rm_start_reading), s.firstReadText.ifEmpty { "-" }),
        StatItem(stringResource(R.string.rm_excerpt_count), excerpts.size.toString()),
        StatItem(stringResource(R.string.rm_review_count), reviews.size.toString()),
    )
}

@Composable
private fun SessionSection(sessionsByMonth: List<MonthReadingSessions>) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(Icons.Default.History, stringResource(R.string.rm_reading_session))
            Spacer(Modifier.height(8.dp))
            if (sessionsByMonth.isEmpty()) {
                EmptyMessage(stringResource(R.string.rm_no_session))
            } else {
                sessionsByMonth.forEach { month ->
                    AppText(
                        text = month.monthTitle,
                        style = LegadoTheme.typography.titleMedium,
                        color = LegadoTheme.colorScheme.primary,
                    )
                    month.days.forEach { day ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(
                                text = formatDay(day.date),
                                style = LegadoTheme.typography.labelMedium,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            AppText(
                                text = formatReadDuration(day.time),
                                style = LegadoTheme.typography.labelMedium,
                                color = LegadoTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ExcerptSection(excerpts: List<Bookmark>) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(Icons.Default.Bookmark, stringResource(R.string.rm_book_excerpts))
            Spacer(Modifier.height(8.dp))
            if (excerpts.isEmpty()) {
                EmptyMessage(stringResource(R.string.rm_no_bookmark))
            } else {
                excerpts.forEach { bm ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        AppText(
                            text = bm.content,
                            style = LegadoTheme.typography.bodyMedium,
                            color = LegadoTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AppText(
                            text = "${bm.chapterName} · ${formatDate(bm.time)}",
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = LegadoTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(
    reviews: List<BookReview>,
    onAdd: () -> Unit,
    onEdit: (BookReview) -> Unit,
    onDelete: (BookReview) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(Icons.Default.Edit, stringResource(R.string.rm_book_reviews))
                Spacer(Modifier.weight(1f))
                AppIconButton(onClick = onAdd) {
                    AppIcon(Icons.Default.Add, contentDescription = null, tint = LegadoTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (reviews.isEmpty()) {
                EmptyMessage(stringResource(R.string.rm_no_review))
                TextCard(stringResource(R.string.rm_add_review), onClick = onAdd)
            } else {
                reviews.forEach { rv ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        AppText(
                            text = rv.reviewContent,
                            style = LegadoTheme.typography.bodyMedium,
                            color = LegadoTheme.colorScheme.onSurface,
                        )
                        AppText(
                            text = formatDate(rv.createTime),
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppIconButton(onClick = { onEdit(rv) }) {
                            AppIcon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = LegadoTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        AppIconButton(onClick = { onDelete(rv) }) {
                            AppIcon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = LegadoTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = LegadoTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TagSection(
    tags: List<BookTag>,
    onAdd: () -> Unit,
    onRemove: (BookTag) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(Icons.Default.LocalOffer, stringResource(R.string.rm_book_tags))
                Spacer(Modifier.weight(1f))
                AppIconButton(onClick = onAdd) {
                    AppIcon(Icons.Default.Add, contentDescription = null, tint = LegadoTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (tags.isEmpty()) {
                EmptyMessage(stringResource(R.string.rm_no_tag))
                TextCard(stringResource(R.string.rm_add_tag), onClick = onAdd)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        TextCard(text = tag.name, onClick = { onRemove(tag) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtagonistSection(
    protagonists: List<BookProtagonist>,
    onAdd: () -> Unit,
    onExtract: () -> Unit,
    onRemove: (BookProtagonist) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(Icons.Default.Person, stringResource(R.string.rm_protagonists))
                Spacer(Modifier.weight(1f))
                AppIconButton(onClick = onExtract) {
                    AppIcon(Icons.Default.AutoAwesome, contentDescription = null, tint = LegadoTheme.colorScheme.primary)
                }
                AppIconButton(onClick = onAdd) {
                    AppIcon(Icons.Default.Add, contentDescription = null, tint = LegadoTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (protagonists.isEmpty()) {
                EmptyMessage(stringResource(R.string.rm_no_protagonist))
                TextCard(stringResource(R.string.rm_add_protagonist), onClick = onAdd)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    protagonists.forEach { p ->
                        TextCard(text = p.name, onClick = { onRemove(p) })
                    }
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))

private fun formatDay(millis: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(millis))

private fun shareBookplate(context: Context, uiState: ReadingMemoryDetailUiState) {
    val book = uiState.book ?: return
    val s = uiState.stats
    val text = buildString {
        appendLine("📖 ${book.name} / ${book.author}")
        appendLine("阅读时长：${formatReadDuration(s.totalReadTime)}")
        appendLine("阅读天数：${s.readingDays} 天")
        appendLine("进度：${s.progressPercent}%（${s.readChapterIndex}/${s.totalChapterNum} 章）")
        appendLine("单日最久：${formatReadDuration(s.maxDayReadTime)}")
        s.totalReadWordsWan?.let { appendLine("累计字数：${String.format(Locale.CHINA, "%.1f 万字", it)}") }
        appendLine("状态：${s.kindText}")
        if (uiState.tags.isNotEmpty()) {
            appendLine("标签：${uiState.tags.joinToString("、") { it.name }}")
        }
        if (uiState.protagonists.isNotEmpty()) {
            appendLine("主角：${uiState.protagonists.joinToString("、") { it.name }}")
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, book.name))
}
