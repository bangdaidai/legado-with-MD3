package io.legado.app.ui.book.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.legado.app.R
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.OptionDropdownCard
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.checkBox.CheckboxItem
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AnimatedTextLine
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCharacterDetailScreen(
    state: CharacterDetailUiState,
    onIntent: (CharacterDetailIntent) -> Unit,
    effects: Flow<CharacterDetailEffect>,
    onBack: () -> Unit,
    onPickAvatar: () -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is CharacterDetailEffect.ShowToast -> context.toastOnUi(effect.message)
                CharacterDetailEffect.NavigateBack -> onBack()
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.character_detail),
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    if (state.characterId != null) {
                        TopBarActionButton(
                            onClick = { showDeleteDialog = true },
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                AppCircularProgressIndicator()
            }
        } else {
            CharacterDetailContent(
                state = state,
                onIntent = onIntent,
                onPickAvatar = onPickAvatar,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

    CharacterDeleteDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        onDelete = { deleteRelations, deleteEvents ->
            onIntent(CharacterDetailIntent.Delete(deleteRelations, deleteEvents))
            showDeleteDialog = false
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterDetailContent(
    state: CharacterDetailUiState,
    onIntent: (CharacterDetailIntent) -> Unit,
    onPickAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showProfileDialog by remember { mutableStateOf(false) }
    var dialogName by remember(state.name) { mutableStateOf(state.name) }
    var dialogAliases by remember(state.aliasesText) { mutableStateOf(state.aliasesText) }
    var dialogRole by remember(state.role) { mutableStateOf(state.role) }
    var dialogVoiceGender by remember(state.voiceGender) { mutableStateOf(state.voiceGender) }
    var dialogPool by remember(state.voicePool) { mutableStateOf(state.voicePool) }
    var dialogIsProtagonist by remember(state.isProtagonist) { mutableStateOf(state.isProtagonist) }

    /** 打开档案弹窗：名字那一块和标签排里的角色小牌都走这一个入口 */
    val openProfileDialog: () -> Unit = {
        dialogName = state.name
        dialogAliases = state.aliasesText
        dialogRole = state.role
        dialogVoiceGender = state.voiceGender
        dialogPool = state.voicePool
        dialogIsProtagonist = state.isProtagonist
        showProfileDialog = true
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CharacterHeader(
            name = state.name,
            aliasesText = state.aliasesText,
            avatarUri = state.avatarUri,
            onPickAvatar = onPickAvatar,
            onInfoClick = openProfileDialog,
        )
        KnowledgeFlowTagEditor(
            title = stringResource(R.string.character_tags),
            tags = state.tags,
            onAddTag = { onIntent(CharacterDetailIntent.AddTag(it)) },
            onRemoveTag = { onIntent(CharacterDetailIntent.RemoveTag(it)) },
            showCloseIcon = true,
            // 角色、声音性别、声音池不再挤在名字旁边，改成跟标签一样平铺在这一排里；
            // 它们不带叉（要改的是档案本身），点一下开的是同一份档案弹窗。
            leadingChips = characterAttributeChips(
                role = state.role,
                voiceGender = state.voiceGender,
                voicePool = state.voicePool,
            ),
            onLeadingChipClick = openProfileDialog,
        )
        KnowledgeEditFieldCard(
            label = stringResource(R.string.character_personality),
            value = state.personality,
            onValueChange = { onIntent(CharacterDetailIntent.SetPersonality(it)) },
            multiline = true,
        )
        KnowledgeEditFieldCard(
            label = stringResource(R.string.character_summary),
            value = state.summary,
            onValueChange = { onIntent(CharacterDetailIntent.SetSummary(it)) },
            multiline = true,
        )
        CharacterReadonlySection(
            title = stringResource(R.string.character_events),
            emptyText = stringResource(R.string.character_events_empty),
            isEmpty = state.events.isEmpty(),
        ) {
            state.events.forEach { event ->
                InfoCard(
                    title = event.title,
                    body = event.content,
                )
            }
        }
        CharacterReadonlySection(
            title = stringResource(R.string.character_relations),
            emptyText = stringResource(R.string.character_relations_empty),
            isEmpty = state.relations.isEmpty(),
        ) {
            state.relations.forEach { relation ->
                InfoCard(
                    title = "${relation.fromName} - ${relation.toName}".trim(' ', '-'),
                    body = listOf(relation.relationType, relation.attitude, relation.summary)
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )
            }
        }
    }

    AppAlertDialog(
        show = showProfileDialog,
        onDismissRequest = { showProfileDialog = false },
        title = stringResource(R.string.character_detail),
        confirmText = stringResource(R.string.apply),
        onConfirm = {
            // 弹窗里改了几栏都算一件事：只发一个动作、只写一行，不按栏拆成多个动作各存一遍整行
            onIntent(
                CharacterDetailIntent.ApplyProfile(
                    name = dialogName,
                    aliasesText = dialogAliases,
                    role = dialogRole,
                    voiceGender = dialogVoiceGender,
                    voicePool = dialogPool,
                    isProtagonist = dialogIsProtagonist,
                )
            )
            showProfileDialog = false
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { showProfileDialog = false },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = dialogName,
                    onValueChange = { dialogName = it },
                    label = stringResource(R.string.character_name),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                AppTextField(
                    value = dialogAliases,
                    onValueChange = { dialogAliases = it },
                    label = stringResource(R.string.character_aliases),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                // 一行两个选项卡片：角色+主角、声音性别+声音池
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileRoleDropdown(
                        selectedRole = dialogRole,
                        onRoleSelected = { role ->
                            dialogRole = role
                            // 男主/女配这类定位本身带性别：选完顺手把声音性别那一格改出来给用户看见，
                            // 之后仍可手改。这里只动弹窗里的显示，落库仍然只有「应用」那一次。
                            impliedVoiceGenderOf(role)?.let { dialogVoiceGender = it }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NormalCard(
                        modifier = Modifier.weight(1f),
                        onClick = { dialogIsProtagonist = !dialogIsProtagonist },
                        cornerRadius = 12.dp,
                        containerColor = if (dialogIsProtagonist) {
                            LegadoTheme.colorScheme.secondaryContainer
                        } else {
                            LegadoTheme.colorScheme.surfaceContainerLow
                        },
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
                                    text = "主角",
                                    style = LegadoTheme.typography.labelSmall,
                                    color = if (dialogIsProtagonist) {
                                        LegadoTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        LegadoTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                AppText(
                                    text = if (dialogIsProtagonist) "是" else "否",
                                    style = LegadoTheme.typography.bodyMedium,
                                    color = if (dialogIsProtagonist) {
                                        LegadoTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        LegadoTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            if (dialogIsProtagonist) {
                                AppIcon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = LegadoTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileVoiceTraitDropdown(
                        label = stringResource(R.string.character_voice_gender),
                        text = voiceGenderDisplayName(dialogVoiceGender),
                        options = BookCharacterProfile.ALL_VOICE_GENDERS,
                        displayName = { voiceGenderDisplayName(it) },
                        onSelected = { dialogVoiceGender = it },
                        modifier = Modifier.weight(1f),
                    )
                    ProfileVoiceTraitDropdown(
                        label = stringResource(R.string.character_voice_age_band),
                        text = dialogPool.ifEmpty { stringResource(R.string.cast_unassigned) },
                        options = buildList {
                            add("")
                            addAll(state.poolNames)
                            if (dialogPool.isNotEmpty() && dialogPool !in state.poolNames) add(dialogPool)
                        },
                        displayName = { pool -> pool.ifEmpty { stringResource(R.string.cast_unassigned) } },
                        onSelected = { dialogPool = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileVoiceTraitDropdown(
    label: String,
    text: String,
    options: List<String>,
    displayName: @Composable (String) -> String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionDropdownCard(
        label = label,
        text = text,
        options = options,
        optionLabel = { displayName(it) },
        onOptionClick = onSelected,
        modifier = modifier,
    )
}

/** 弹窗里的选项卡片已提升为公共组件 [OptionDropdownCard]，人物详情与配音编辑共用。 */

@Composable
private fun CharacterDeleteDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onDelete: (deleteRelations: Boolean, deleteEvents: Boolean) -> Unit,
) {
    var deleteRelations by remember { mutableStateOf(false) }
    var deleteEvents by remember { mutableStateOf(false) }
    AppAlertDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.delete),
        text = stringResource(R.string.character_delete_message),
        confirmText = stringResource(R.string.delete),
        onConfirm = { onDelete(deleteRelations, deleteEvents) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckboxItem(
                    stringResource(R.string.character_delete_relations),
                    checked = deleteRelations
                ) { deleteRelations = it }
                CheckboxItem(
                    stringResource(R.string.character_delete_events),
                    checked = deleteEvents
                ) { deleteEvents = it }
            }
        },
    )
}

@Composable
private fun voiceGenderDisplayName(value: String): String = when (value) {
    BookCharacterProfile.VOICE_GENDER_MALE -> stringResource(R.string.voice_gender_male)
    BookCharacterProfile.VOICE_GENDER_FEMALE -> stringResource(R.string.voice_gender_female)
    else -> stringResource(R.string.voice_gender_unknown)
}

/**
 * 角色、声音性别、声音池这三样改到标签那一排里当小牌出现：没选的角色、没分到的声音池、
 * 还是「未知」的性别都不占位，剩下几枚就跟标签一样平铺换行。
 */
@Composable
private fun characterAttributeChips(
    role: String,
    voiceGender: String,
    voicePool: String,
): List<String> {
    val roleLabel = when (role) {
        BookCharacterProfile.ROLE_MALE_LEAD -> stringResource(R.string.role_male_lead)
        BookCharacterProfile.ROLE_FEMALE_LEAD -> stringResource(R.string.role_female_lead)
        BookCharacterProfile.ROLE_MALE_SUPPORTING -> stringResource(R.string.role_male_supporting)
        BookCharacterProfile.ROLE_FEMALE_SUPPORTING -> stringResource(R.string.role_female_supporting)
        BookCharacterProfile.ROLE_SUPPORTING -> stringResource(R.string.role_supporting)
        else -> ""
    }
    val chips = mutableListOf<String>()
    if (roleLabel.isNotBlank()) {
        chips += roleLabel
    }
    if (voiceGender != BookCharacterProfile.VOICE_GENDER_UNKNOWN) {
        chips += voiceGenderDisplayName(voiceGender)
    }
    if (voicePool.isNotBlank()) {
        chips += voicePool
    }
    return chips
}

/** 角色定位隐含的声音性别；配角或没选定位时返回 null，说明看不出来，交给用户自己选 */
private fun impliedVoiceGenderOf(role: String): String? = when (role) {
    BookCharacterProfile.ROLE_MALE_LEAD,
    BookCharacterProfile.ROLE_MALE_SUPPORTING -> BookCharacterProfile.VOICE_GENDER_MALE

    BookCharacterProfile.ROLE_FEMALE_LEAD,
    BookCharacterProfile.ROLE_FEMALE_SUPPORTING -> BookCharacterProfile.VOICE_GENDER_FEMALE

    else -> null
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileRoleDropdown(
    selectedRole: String,
    onRoleSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roleDisplayNames = mapOf(
        BookCharacterProfile.ROLE_MALE_LEAD to stringResource(R.string.role_male_lead),
        BookCharacterProfile.ROLE_FEMALE_LEAD to stringResource(R.string.role_female_lead),
        BookCharacterProfile.ROLE_MALE_SUPPORTING to stringResource(R.string.role_male_supporting),
        BookCharacterProfile.ROLE_FEMALE_SUPPORTING to stringResource(R.string.role_female_supporting),
    )
    OptionDropdownCard(
        label = stringResource(R.string.character_role),
        text = roleDisplayNames[selectedRole] ?: "—",
        options = BookCharacterProfile.ALL_ROLES,
        optionLabel = { roleDisplayNames[it] ?: it },
        onOptionClick = { role ->
            onRoleSelected(if (selectedRole == role) "" else role)
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterHeader(
    name: String,
    aliasesText: String,
    avatarUri: String,
    onPickAvatar: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val avatarLoadFailed = remember(avatarUri) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCard(
            onClick = onPickAvatar,
            cornerRadius = 64.dp,
            containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUri.isNotBlank() && !avatarLoadFailed.value) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { avatarLoadFailed.value = true },
                    )
                } else {
                    AppIcon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onInfoClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnimatedTextLine(
                text = name.ifBlank { "—" },
                style = LegadoTheme.typography.titleMedium,
            )
            if (aliasesText.isNotBlank()) {
                AnimatedTextLine(
                    text = aliasesText,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            } else {
                AnimatedTextLine(
                    text = stringResource(R.string.character_no_aliases),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CharacterReadonlySection(
    title: String,
    emptyText: String,
    isEmpty: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(text = title, style = LegadoTheme.typography.titleMedium)
        if (isEmpty) {
            GlassCard {
                Box(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyMessage(message = emptyText)
                }
            }
        } else {
            content()
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnimatedTextLine(
                text = title.ifBlank { stringResource(R.string.character_unknown) },
                style = LegadoTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedTextLine(
                text = body.ifBlank { stringResource(R.string.character_no_summary) },
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 4,
            )
        }
    }
}
