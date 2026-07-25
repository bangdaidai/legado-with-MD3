package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.ui.theme.AppTheme
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.TagColorUtils
import kotlinx.coroutines.flow.flow

private fun tagColorInt(bookTag: BookTag): Int {
    return if (bookTag.color != 0) bookTag.color else TagColorUtils.generateRandomColor()
}

@Composable
private fun TagChip(
    bookTag: BookTag,
    bookCount: Int,
    onClick: () -> Unit,
) {
    val bg = Color(tagColorInt(bookTag))
    val content = if (ColorUtils.isColorLight(bg.toArgb())) Color.Black else Color.White
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = bookTag.name, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            text = stringResource(R.string.book_count, bookCount),
            color = content.copy(alpha = 0.8f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TagFlowRow(
    tags: List<Pair<BookTag, Int>>,
    onTagClick: (BookTag) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        tags.forEach { (tag, count) ->
            TagChip(bookTag = tag, bookCount = count, onClick = { onTagClick(tag) })
        }
    }
}

@Composable
private fun TagListWithCounts(tags: List<BookTag>, onTagClick: (BookTag) -> Unit) {
    val key = tags.joinToString { it.id.toString() }
    val counts by remember(key) {
        flow { emit(tags.map { it to BookTagRepository.countBooks(it.id) }) }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    TagFlowRow(tags = counts, onTagClick = onTagClick)
}

@Composable
private fun GroupTitle(
    name: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text(
            "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExcludedGroupCard(
    excluded: List<ExcludedTag>,
    onAdd: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.excluded_tags),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.count_items, excluded.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = stringResource(R.string.excluded_tag_pattern),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                        Text(stringResource(R.string.regex), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = {
                        if (pattern.isNotBlank()) {
                            onAdd(pattern.trim(), isRegex)
                            pattern = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (excluded.isEmpty()) {
                    Text(
                        stringResource(R.string.no_excluded_tags),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    excluded.forEach { et ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (et.isRegex) {
                                Text(
                                    stringResource(R.string.regex),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                et.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemove(et.name) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookTagManageContent(
    groups: List<BookTagGroup>,
    allTags: List<BookTag>,
    excluded: List<ExcludedTag>,
    onNewTag: () -> Unit,
    onOpenDetail: (BookTag) -> Unit,
    onAddExcluded: (String, Boolean) -> Unit,
    onRemoveExcluded: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var selectedGroupId by remember { mutableStateOf<Long?>(null) }
    val expandedGroups = remember { mutableStateMapOf<Long?, Boolean>() }

    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                title = stringResource(R.string.book_tag_manage),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewTag) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_tag))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedGroupId == null,
                            onClick = { selectedGroupId = null },
                            label = { Text(stringResource(R.string.all_groups)) }
                        )
                    }
                    items(groups) { group ->
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { selectedGroupId = group.id },
                            label = { Text(group.name) }
                        )
                    }
                }
            }

            item {
                ExcludedGroupCard(
                    excluded = excluded,
                    onAdd = onAddExcluded,
                    onRemove = onRemoveExcluded
                )
            }

            val ungroupedLabel = stringResource(R.string.ungrouped)
            val sections = remember(groups, allTags, selectedGroupId) {
                val gList = if (selectedGroupId == null) groups else groups.filter { it.id == selectedGroupId }
                val result = gList.map { group ->
                    group to allTags.filter { it.groupId == group.id }
                }.toMutableList()
                if (selectedGroupId == null) {
                    val ungrouped = allTags.filter { it.groupId == 0L }
                    if (ungrouped.isNotEmpty()) {
                        result.add(
                            BookTagGroup(id = -1, name = ungroupedLabel) to ungrouped
                        )
                    }
                }
                result
            }

            sections.forEach { (group, tags) ->
                val expanded = expandedGroups.getOrPut(group.id) { true }
                item(key = "header_${group.id}") {
                    GroupTitle(name = group.name, count = tags.size, expanded = expanded) {
                        expandedGroups[group.id] = !expanded
                    }
                }
                if (expanded) {
                    item(key = "body_${group.id}") {
                        TagListWithCounts(tags = tags, onTagClick = onOpenDetail)
                    }
                }
            }
        }
    }
}

@Composable
fun BookTagManageScreen(
    viewModel: BookTagManageViewModel,
    onBack: () -> Unit,
    onOpenDetail: (BookTag) -> Unit,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val allTags by viewModel.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val excluded by viewModel.excludedTags.collectAsStateWithLifecycle(initialValue = emptyList())

    var showEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookTag?>(null) }

    AppTheme(AppUiConfiguration()) {
        BookTagManageContent(
            groups = groups,
            allTags = allTags,
            excluded = excluded,
            onNewTag = { editing = null; showEdit = true },
            onOpenDetail = onOpenDetail,
            onAddExcluded = { name, regex -> viewModel.addExcludedTag(name, regex) },
            onRemoveExcluded = { name -> viewModel.removeExcludedTag(name) },
            onBack = onBack,
        )
        if (showEdit) {
            BookTagEditSheet(
                show = true,
                tag = editing,
                groups = groups,
                onDismissRequest = { showEdit = false },
                onSave = { tag ->
                    viewModel.saveTag(tag)
                    showEdit = false
                },
                onDelete = { tag ->
                    viewModel.deleteTag(tag)
                    showEdit = false
                },
                onCreateGroup = { viewModel.createGroup(it) },
            )
        }
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
private fun BookTagManagePreview() {
    LegadoTheme {
        BookTagManageContent(
            groups = listOf(
                BookTagGroup(id = 1, name = "男频"),
                BookTagGroup(id = 2, name = "女频")
            ),
            allTags = listOf(
                BookTag(id = 1, groupId = 1, name = "玄幻"),
                BookTag(id = 2, groupId = 2, name = "言情")
            ),
            excluded = listOf(ExcludedTag(id = 1, name = "短篇")),
            onNewTag = {},
            onOpenDetail = { _ -> },
            onAddExcluded = { _, _ -> },
            onRemoveExcluded = {},
            onBack = {}
        )
    }
}
