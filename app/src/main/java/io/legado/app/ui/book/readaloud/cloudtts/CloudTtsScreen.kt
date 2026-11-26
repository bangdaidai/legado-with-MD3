package io.legado.app.ui.book.readaloud.cloudtts

import android.content.ClipData
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.profile
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.script.TtsRandomNumberStore
import io.legado.app.help.readaloud.script.TtsScriptEngineClient
import io.legado.app.help.readaloud.script.TtsScriptOption
import io.legado.app.help.readaloud.script.TtsScriptOptionType
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppFloatingActionButtonMenu
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.FabMenuItem
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog
import io.legado.app.ui.widget.components.importComponents.SourceInputDialog
import io.legado.app.ui.widget.components.menu.AppAction
import io.legado.app.ui.widget.components.menu.AppActionGroup
import io.legado.app.ui.widget.components.menu.AppActionMenu
import io.legado.app.ui.widget.components.menu.AppAnchoredPanel
import io.legado.app.ui.widget.components.menu.AppChoiceDropdown
import io.legado.app.ui.widget.components.menu.AppChoiceOption
import io.legado.app.ui.widget.components.menu.AppMenuAnchor
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.modalBottomSheet.OptionCard
import io.legado.app.ui.widget.components.modalBottomSheet.OptionSheet
import io.legado.app.ui.widget.components.modalBottomSheet.SheetAction
import io.legado.app.ui.widget.components.modalBottomSheet.SheetMenuAction
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CloudTtsScreen(
    state: CloudTtsUiState,
    onIntent: (CloudTtsIntent) -> Unit,
    effects: Flow<CloudTtsEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val selectedEngineKey = state.selectedEngineKey
    val selectedEngineTitle = when (val key = selectedEngineKey) {
        null -> null
        else -> when (key.engineType) {
            ReadAloudVoice.ENGINE_CLOUD -> state.engines
                .firstOrNull { it.id == key.engineId }?.title

            ReadAloudVoice.ENGINE_HTTP -> state.httpEngines
                .firstOrNull { it.engineId == key.engineId }?.title

            ReadAloudVoice.ENGINE_SYSTEM -> state.systemEngines
                .firstOrNull { it.engineId == key.engineId }?.title

            else -> null
        }
    }
    val selectedEngineLoginUrl = selectedEngineKey
        ?.takeIf { it.engineType == ReadAloudVoice.ENGINE_HTTP }
        ?.let { key -> state.httpEngines.firstOrNull { it.engineId == key.engineId }?.loginUrl }
        .orEmpty()
    val handleBack = {
        when {
            selectedEngineKey == null -> onBack()
            state.voiceBatchMode -> onIntent(CloudTtsIntent.ToggleVoiceBatchMode)
            else -> onIntent(CloudTtsIntent.CloseEngine)
        }
    }
    BackHandler(enabled = selectedEngineKey != null) { handleBack() }
    var showAddEngineSheet by remember { mutableStateOf(false) }
    var showHttpTtsImportSheet by remember { mutableStateOf(false) }
    var showHttpTtsUrlInput by remember { mutableStateOf(false) }
    // 行尾试听按钮长按：弹出试听文本悬浮窗（改默认文本 / 直接试听草稿）
    var previewTextVoice by remember { mutableStateOf<CloudTtsVoiceItemUi?>(null) }
    // 常驻组合 + show：miuix 的窗口靠 show 驱动退场动画，条件组合会直接丢动画
    VoicePreviewTextDialog(
        voice = previewTextVoice,
        initialText = state.previewText,
        onDismiss = { previewTextVoice = null },
        onPreview = { voice, draft -> onIntent(CloudTtsIntent.PreviewVoice(voice.id, draft)) },
        onConfirm = { draft ->
            onIntent(CloudTtsIntent.SavePreviewText(draft))
            previewTextVoice = null
        },
    )
    val player = remember { MediaPlayer() }
    /** 批量操作的右下角折叠菜单：只在批量模式下有意义，退出就收起来。 */
    var voiceBatchMenuExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.voiceBatchMode) {
        if (!state.voiceBatchMode) voiceBatchMenuExpanded = false
    }
    val errorTitle = stringResource(R.string.cloud_tts_error)
    val previewPlaybackFailed = stringResource(R.string.cloud_tts_preview_playback_failed)
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is CloudTtsEffect.ShowToast -> context.toastOnUi(effect.message)
                is CloudTtsEffect.CopyText -> {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(errorTitle, effect.text)))
                    context.toastOnUi(effect.message)
                }
                is CloudTtsEffect.PlayPreview -> runCatching {
                    player.reset()
                    player.setDataSource(effect.path)
                    player.prepare()
                    player.start()
                }.onFailure { onIntent(CloudTtsIntent.ReportError(
                    "$previewPlaybackFailed\n\n${it::class.java.simpleName}: ${it.localizedMessage.orEmpty()}"
                )) }
                CloudTtsEffect.OpenHttpTtsImportPicker,
                CloudTtsEffect.OpenHttpTtsExportPicker,
                is CloudTtsEffect.OpenHttpTtsLogin -> Unit
            }
        }
    }
    val topBarMenuGroups = if (selectedEngineKey == null) {
        persistentListOf(
            AppActionGroup(
                id = "engine_list",
                actions = persistentListOf(
                    AppAction("import_tts", stringResource(R.string.import_tts)),
                    AppAction("export_tts_file", stringResource(R.string.export)),
                    AppAction("copy_tts_url", stringResource(R.string.copy_url)),
                    AppAction("clear_tts_cache", stringResource(R.string.clear_cache)),
                ),
            )
        )
    } else {
        buildList {
            add(
                AppActionGroup(
                    id = "engine_detail",
                    actions = buildList {
                        add(AppAction("add_voice", stringResource(R.string.cloud_tts_add_voice)))
                        add(
                            AppAction(
                                "set_default_engine",
                                stringResource(R.string.cloud_tts_set_default_engine),
                            )
                        )
                        if (selectedEngineLoginUrl.isNotBlank()) {
                            add(AppAction("login", stringResource(R.string.login)))
                        }
                    }.toPersistentList(),
                )
            )
            add(
                AppActionGroup(
                    id = "voice_gender",
                    actions = persistentListOf(
                        AppAction(
                            "gender:",
                            stringResource(R.string.voice_gender_all),
                            selected = state.voiceGenderFilter.isBlank(),
                        ),
                        AppAction(
                            "gender:${ReadAloudVoiceTraits.GENDER_MALE}",
                            stringResource(R.string.voice_gender_male),
                            selected = state.voiceGenderFilter == ReadAloudVoiceTraits.GENDER_MALE,
                        ),
                        AppAction(
                            "gender:${ReadAloudVoiceTraits.GENDER_FEMALE}",
                            stringResource(R.string.voice_gender_female),
                            selected = state.voiceGenderFilter == ReadAloudVoiceTraits.GENDER_FEMALE,
                        ),
                        AppAction(
                            "gender:${ReadAloudVoiceTraits.GENDER_UNKNOWN}",
                            stringResource(R.string.voice_gender_unknown),
                            selected = state.voiceGenderFilter == ReadAloudVoiceTraits.GENDER_UNKNOWN,
                        ),
                    ),
                )
            )
        }.toPersistentList()
    }
    val onTopBarMenuAction: (String) -> Unit = { id ->
        val key = selectedEngineKey
        when {
            key == null -> when (id) {
                "import_tts" -> showHttpTtsImportSheet = true
                "export_tts_file" -> onIntent(CloudTtsIntent.ExportHttpTtsFile)
                "copy_tts_url" -> onIntent(CloudTtsIntent.ExportHttpTtsUrl)
                "clear_tts_cache" -> onIntent(CloudTtsIntent.ClearTtsCache)
            }

            id == "add_voice" -> {
                if (key.engineType == ReadAloudVoice.ENGINE_SYSTEM && key.engineId.isBlank()) {
                    onIntent(CloudTtsIntent.AddVoice)
                } else {
                    onIntent(CloudTtsIntent.AddVoiceForEngine(key.engineType, key.engineId))
                }
            }

            id == "set_default_engine" ->
                onIntent(CloudTtsIntent.SetDefaultEngine(key.engineType, key.engineId))

            id == "login" -> onIntent(CloudTtsIntent.OpenHttpTtsLogin(key.engineId))
            id.startsWith("gender:") ->
                onIntent(CloudTtsIntent.SetVoiceGenderFilter(id.removePrefix("gender:")))
        }
    }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = selectedEngineTitle ?: stringResource(R.string.read_aloud_engines_and_voices),
                navigationIcon = { TopBarNavigationButton(onClick = handleBack) },
                actions = {
                    if (selectedEngineKey != null) {
                        TopBarActionButton(
                            onClick = { onIntent(CloudTtsIntent.ToggleVoiceBatchMode) },
                            imageVector = if (state.voiceBatchMode) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.SelectAll
                            },
                            contentDescription = stringResource(
                                if (state.voiceBatchMode) {
                                    R.string.cloud_tts_exit_batch
                                } else {
                                    R.string.cloud_tts_batch_mode
                                }
                            ),
                        )
                    }
                    AppActionMenu(
                        groups = topBarMenuGroups,
                        onAction = onTopBarMenuAction,
                    ) {
                        AppIcon(
                            imageVector = AppIcons.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                            modifier = this.modifier,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 批量操作放右下角折叠菜单：列表顶部那条横排按钮划到下面就够不着了
            if (selectedEngineKey != null && state.voiceBatchMode) {
                val selectedCount = state.selectedVoiceIds.size
                // 删除只作用于用户/外部目录音色，菜单项按这个子集决定显隐与计数；
                // 「加入声音池」不限 managedBy，用全量 selectedCount。
                val deletableSelectedCount = state.voices.count { voice ->
                    voice.deletable && voice.id in state.selectedVoiceIds
                }
                AppFloatingActionButtonMenu(
                    // 组件内部自带 horizontal 16 / vertical 24 的内边距，不抵消的话这颗
                    // 会比平时那颗 FAB 往里缩，进出批量模式时位置一跳（用户说的「割裂」）。
                    // 数值是量出来的：真机上两颗左沿都落在 1188px，纵向 24dp 会低 28px
                    // （560dpi = 8dp），所以 x 补满 16、y 只补 16。
                    modifier = Modifier.offset(x = 16.dp, y = 16.dp),
                    expanded = voiceBatchMenuExpanded,
                    onExpandedChange = { voiceBatchMenuExpanded = it },
                    items = buildList {
                        add(
                            FabMenuItem(
                                icon = Icons.Default.SelectAll,
                                label = stringResource(R.string.select_all),
                                action = { onIntent(CloudTtsIntent.ToggleSelectAllVoices) },
                            ),
                        )
                        add(
                            FabMenuItem(
                                icon = Icons.Default.PlaylistAdd,
                                label = stringResource(
                                    R.string.cloud_tts_add_selected_to_pool, selectedCount,
                                ),
                                action = { onIntent(CloudTtsIntent.RequestAddToPool(emptyList())) },
                            ),
                        )
                        if (deletableSelectedCount > 0) {
                            add(
                                FabMenuItem(
                                    icon = Icons.Default.Delete,
                                    label = stringResource(
                                        R.string.cloud_tts_delete_selected_count,
                                        deletableSelectedCount,
                                    ),
                                    action = { onIntent(CloudTtsIntent.RequestDeleteSelectedVoices) },
                                ),
                            )
                        }
                        add(
                            FabMenuItem(
                                icon = Icons.Default.Close,
                                label = stringResource(R.string.cancel),
                                action = { onIntent(CloudTtsIntent.ToggleVoiceBatchMode) },
                            ),
                        )
                    },
                )
            } else {
                AppFloatingActionButton(
                    onClick = {
                        val key = selectedEngineKey
                        when {
                            key == null -> showAddEngineSheet = true
                            key.engineType == ReadAloudVoice.ENGINE_SYSTEM && key.engineId.isBlank() ->
                                onIntent(CloudTtsIntent.AddVoice)

                            else -> onIntent(
                                CloudTtsIntent.AddVoiceForEngine(key.engineType, key.engineId)
                            )
                        }
                    },
                    icon = if (selectedEngineKey == null) {
                        Icons.Default.Add
                    } else {
                        Icons.Default.RecordVoiceOver
                    },
                    tooltipText = stringResource(
                        if (selectedEngineKey == null) R.string.cloud_tts_add_engine
                        else R.string.cloud_tts_add_voice
                    ),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            if (selectedEngineKey != null) {
                item { EngineSectionTitle(R.string.cloud_tts_saved_voices) }
                if (state.voices.isEmpty() && !state.loading) {
                    item {
                        AppText(
                            stringResource(R.string.cloud_tts_no_engine_voices),
                            Modifier.padding(24.dp),
                        )
                    }
                }
                items(state.voices, key = { "voice:${it.id}" }) { voice ->
                    VoiceCardItem(
                        title = voice.title,
                        // 第一行：紧跟名字的性别/语言/风格小图标
                        tags = {
                            Row(
                                modifier = Modifier,
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (voice.isDefaultNarrator) {
                                    VoiceTraitTag(
                                        text = stringResource(R.string.cloud_tts_default_narrator_marker),
                                        container = LegadoTheme.colorScheme.primaryContainer,
                                        content = LegadoTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                voice.genderLabel.takeIf(String::isNotBlank)?.let {
                                    VoiceTraitTag(
                                        text = when (voice.gender) {
                                            io.legado.app.help.readaloud.ReadAloudVoiceTraits.GENDER_MALE -> "♂"
                                            else -> "♀"
                                        },
                                        container = if (voice.gender ==
                                            io.legado.app.help.readaloud.ReadAloudVoiceTraits.GENDER_MALE
                                        ) {
                                            LegadoTheme.colorScheme.primaryContainer
                                        } else {
                                            LegadoTheme.colorScheme.secondaryContainer
                                        },
                                        content = if (voice.gender ==
                                            io.legado.app.help.readaloud.ReadAloudVoiceTraits.GENDER_MALE
                                        ) {
                                            LegadoTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            LegadoTheme.colorScheme.onSecondaryContainer
                                        },
                                    )
                                }
                                voice.languageLabel.takeIf(String::isNotBlank)?.let {
                                    VoiceTraitTag(
                                        text = it.take(6),
                                        container = LegadoTheme.colorScheme.tertiaryContainer,
                                        content = LegadoTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                                voice.styleLabel.takeIf(String::isNotBlank)?.let {
                                    VoiceTraitTag(
                                        text = it,
                                        container = LegadoTheme.colorScheme.surfaceVariant,
                                        content = LegadoTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        // 第二行：池/用户标注等彩色小标签 + 引擎名弱色标签，横向滑动看全
                        scrollableTags = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                voice.secondaryTags.forEachIndexed { index, tag ->
                                    // 五色轮换，与第一行风格块区分
                                    val colors = listOf(
                                        LegadoTheme.colorScheme.secondaryContainer to
                                            LegadoTheme.colorScheme.onSecondaryContainer,
                                        LegadoTheme.colorScheme.tertiaryContainer to
                                            LegadoTheme.colorScheme.onTertiaryContainer,
                                        LegadoTheme.colorScheme.primaryContainer to
                                            LegadoTheme.colorScheme.onPrimaryContainer,
                                    )
                                    val color = colors[index % colors.size]
                                    VoiceTraitTag(
                                        text = tag,
                                        container = color.first,
                                        content = color.second,
                                    )
                                }
                                voice.engineName.takeIf(String::isNotBlank)?.let {
                                    VoiceTraitTag(
                                        text = it,
                                        container = LegadoTheme.colorScheme.surfaceVariant,
                                        content = LegadoTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            if (state.voiceBatchMode) {
                                // 任何音色都能勾选：入池不限 managedBy
                                MediumTonalButton(
                                    onClick = {
                                        onIntent(CloudTtsIntent.ToggleVoiceSelection(voice.id))
                                    },
                                    selected = voice.id in state.selectedVoiceIds,
                                    icon = Icons.Default.Check,
                                    contentDescription =
                                        stringResource(R.string.cloud_tts_batch_mode),
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    MediumTonalButton(
                                        onClick = { onIntent(CloudTtsIntent.PreviewSavedVoice(voice.id)) },
                                        onLongClick = { previewTextVoice = voice },
                                        selected = state.previewingVoiceId == voice.id,
                                        icon = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.voice_preview),
                                    )
                                    VoiceActionsMenu(voice, onIntent)
                                }
                            }
                        },
                        onClick = {
                            when {
                                state.voiceBatchMode ->
                                    onIntent(CloudTtsIntent.ToggleVoiceSelection(voice.id))

                                else -> onIntent(CloudTtsIntent.EditVoice(voice.id))
                            }
                        },
                        onLongClick = if (state.voiceBatchMode) null else {
                            { onIntent(CloudTtsIntent.ToggleDefaultNarratorVoice(voice.id)) }
                        },
                    )
                }
                if (state.excludedVoiceCount > 0) {
                    item {
                        TinyClickableSettingItem(
                            title = stringResource(
                                R.string.cloud_tts_restore_excluded_voices,
                                state.excludedVoiceCount,
                            ),
                            onClick = { onIntent(CloudTtsIntent.RestoreExcludedVoices) },
                        )
                    }
                }
            } else {
                item { EngineSectionTitle(R.string.cloud_tts_cloud_engines) }
                items(
                    state.engines,
                    key = { "engine:${it.key.engineType}:${it.key.engineId}" },
                ) { engine ->
                    EngineCard(
                        engineKey = engine.key,
                        title = engine.title,
                        summary = engine.summary,
                        selectedDefault = engine.selected,
                        voiceCount = engine.voiceCount,
                        hasScript = false,
                        loginUrl = "",
                        onIntent = onIntent,
                    )
                }
                item { EngineSectionTitle(R.string.cloud_tts_http_engines) }
                items(
                    state.httpEngines,
                    key = { "engine:${it.key.engineType}:${it.key.engineId}" },
                ) { engine ->
                    EngineCard(
                        engineKey = engine.key,
                        title = engine.title,
                        summary = engine.summary,
                        selectedDefault = engine.selected,
                        voiceCount = engine.voiceCount,
                        hasScript = engine.hasScript,
                        loginUrl = engine.loginUrl,
                        onIntent = onIntent,
                    )
                }
                item { EngineSectionTitle(R.string.cloud_tts_system_engines) }
                items(
                    state.systemEngines,
                    key = { "engine:${it.key.engineType}:${it.key.engineId}" },
                ) { engine ->
                    EngineCard(
                        engineKey = engine.key,
                        title = engine.title,
                        summary = engine.summary,
                        selectedDefault = engine.selected,
                        voiceCount = engine.voiceCount,
                        hasScript = false,
                        loginUrl = "",
                        onIntent = onIntent,
                    )
                }
            }
        }
    }
    OptionSheet(
        show = showAddEngineSheet,
        onDismissRequest = { showAddEngineSheet = false },
        title = stringResource(R.string.speak_engine),
    ) {
        OptionCard(
            icon = Icons.Default.Cloud,
            text = stringResource(R.string.cloud_tts_add_engine),
            onClick = {
                showAddEngineSheet = false
                onIntent(CloudTtsIntent.AddEngine)
            },
        )
        OptionCard(
            icon = Icons.Default.RecordVoiceOver,
            text = stringResource(R.string.cloud_tts_add_http_engine),
            onClick = {
                showAddEngineSheet = false
                onIntent(CloudTtsIntent.EditHttpTts())
            },
        )
    }
    CloudTtsEngineEditorSheet(state, state.engineEditor, onIntent)
    VoiceEnginePickerSheet(state.showVoiceEnginePicker, state, onIntent)
    TtsVoicePresetEditorSheet(state, state.voiceEditor, onIntent)
    HttpTtsEditorSheet(state.httpTtsEditor, onIntent)
    FilePickerSheet(
        show = showHttpTtsImportSheet,
        onDismissRequest = { showHttpTtsImportSheet = false },
        title = stringResource(R.string.import_tts),
        onSelectSysFile = {
            showHttpTtsImportSheet = false; onIntent(CloudTtsIntent.ImportHttpTtsFile)
        },
        onManualInput = { showHttpTtsImportSheet = false; showHttpTtsUrlInput = true },
        allowExtensions = arrayOf("json", "txt", "js"),
    )
    SourceInputDialog(
        show = showHttpTtsUrlInput,
        title = stringResource(R.string.import_on_line),
        onDismissRequest = { showHttpTtsUrlInput = false },
        onConfirm = {
            showHttpTtsUrlInput = false; onIntent(CloudTtsIntent.ImportHttpTtsSource(it))
        },
    )
    BatchImportDialog(
        title = stringResource(R.string.import_tts),
        importState = state.httpTtsImportState,
        onDismissRequest = { onIntent(CloudTtsIntent.CancelHttpTtsImport) },
        onToggleItem = { onIntent(CloudTtsIntent.ToggleHttpTtsImportSelection(it)) },
        onToggleAll = { onIntent(CloudTtsIntent.ToggleHttpTtsImportAll(it)) },
        onUpdateItem = { index, value ->
            onIntent(
                CloudTtsIntent.UpdateHttpTtsImportItem(
                    index,
                    value
                )
            )
        },
        onConfirm = { onIntent(CloudTtsIntent.SaveImportedHttpTts) },
        itemTitle = { it.name },
        itemSubtitle = { it.url },
    )
    val deleteVoice = state.activeDialog as? CloudTtsDialog.DeleteVoice
    AppAlertDialog(
        show = deleteVoice != null,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissError) },
        title = stringResource(R.string.delete),
        text = deleteVoice?.let {
            stringResource(
                R.string.cloud_tts_delete_voice_message,
                it.title
            )
        },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onIntent(CloudTtsIntent.ConfirmDeleteVoice) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(CloudTtsIntent.DismissError) },
    )
    val renameVoice = state.activeDialog as? CloudTtsDialog.RenameVoice
    if (renameVoice != null) {
        var name by remember(renameVoice) { mutableStateOf(renameVoice.currentName) }
        AppAlertDialog(
            show = true,
            onDismissRequest = { onIntent(CloudTtsIntent.DismissError) },
            title = stringResource(R.string.cloud_tts_rename_voice),
            confirmText = stringResource(R.string.ok),
            onConfirm = { onIntent(CloudTtsIntent.ConfirmRenameVoice(name)) },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { onIntent(CloudTtsIntent.DismissError) },
            content = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { AppText(stringResource(R.string.cloud_tts_rename_voice)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    val deleteVoices = state.activeDialog as? CloudTtsDialog.DeleteVoices
    AppAlertDialog(
        show = deleteVoices != null,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissError) },
        title = stringResource(R.string.delete),
        text = deleteVoices?.let {
            stringResource(R.string.cloud_tts_delete_voices_message, it.count)
        },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onIntent(CloudTtsIntent.ConfirmDeleteSelectedVoices) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(CloudTtsIntent.DismissError) },
    )
    // 弹层一律「常驻组合 + data」：miuix 的窗口靠 show驱动退场动画，条件组合会直接丢动画
    AppModalBottomSheet(
        data = state.poolPicker,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissPoolPicker) },
        title = stringResource(
            R.string.cloud_tts_add_selected_to_pool,
            state.poolPicker?.voiceIds?.size ?: 0,
        ),
    ) { picker ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (picker.voiceLabel.isNotBlank()) {
                    AppText(
                        text = picker.voiceLabel,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (picker.pools.isEmpty()) {
                    AppText(
                        text = stringResource(R.string.cloud_tts_no_pools),
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(picker.pools, key = { "pool:${it.id}" }) { pool ->
                            TinyClickableSettingItem(
                                title = pool.name,
                                description = listOf(
                                    pool.groupName,
                                    stringResource(
                                        R.string.cloud_tts_pool_member_count,
                                        pool.memberCount,
                                    ),
                                ).filter(String::isNotBlank).joinToString(" · "),
                                onClick = { onIntent(CloudTtsIntent.ConfirmAddToPool(pool.id)) },
                            )
                        }
                    }
                }
            }
    }
    val defaultScope = state.activeDialog as? CloudTtsDialog.DefaultEngineScope
    AppAlertDialog(
        show = defaultScope != null,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissError) },
        title = defaultScope?.title ?: stringResource(R.string.read_aloud_default_engine),
        text = stringResource(R.string.speak_engine_apply_scope),
        confirmText = stringResource(R.string.general),
        onConfirm = { onIntent(CloudTtsIntent.ApplyDefaultEngineGlobally) },
        dismissText = stringResource(R.string.book),
        onDismiss = { onIntent(CloudTtsIntent.ApplyDefaultEngineForBook) },
    )
    AppModalBottomSheet(
        data = state.activeDialog as? CloudTtsDialog.Error,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissError) },
        title = stringResource(R.string.cloud_tts_error),
        startSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.DismissError) },
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.close),
        ),
        endSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.CopyError) },
            icon = Icons.Default.ContentCopy,
            contentDescription = stringResource(R.string.cloud_tts_copy_error),
        ),
    ) { error ->
        AppText(
            text = error.message,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun EngineSectionTitle(titleRes: Int) {
    AppText(
        text = stringResource(titleRes),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        style = LegadoTheme.typography.labelMediumEmphasized
    )
}

@Composable
private fun EngineCard(
    engineKey: TtsEngineKey,
    title: String,
    summary: String,
    selectedDefault: Boolean,
    voiceCount: Int,
    hasScript: Boolean,
    loginUrl: String,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    val actions = buildList {
        add(
            AppAction(
                id = "set_default",
                label = stringResource(R.string.cloud_tts_set_default_engine),
                selected = selectedDefault,
            )
        )
        when (engineKey.engineType) {
            ReadAloudVoice.ENGINE_CLOUD -> {
                add(AppAction("edit", stringResource(R.string.edit)))
                add(
                    AppAction(
                        "delete",
                        stringResource(R.string.delete),
                        destructive = true,
                    )
                )
            }

            ReadAloudVoice.ENGINE_HTTP -> {
                if (loginUrl.isNotBlank()) {
                    add(AppAction("login", stringResource(R.string.login)))
                }
                add(AppAction("edit", stringResource(R.string.edit)))
                add(
                    AppAction(
                        "delete",
                        stringResource(R.string.delete),
                        destructive = true,
                    )
                )
            }
        }
    }.toPersistentList()
    VoiceCardItem(
        title = title,
        tags = if (selectedDefault) {
            {
                VoiceTraitTag(
                    text = stringResource(R.string.cloud_tts_default_engine_marker),
                    container = LegadoTheme.colorScheme.primaryContainer,
                    content = LegadoTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else null,
        scrollableTags = {
            Row(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (hasScript) {
                    VoiceTraitTag(
                        text = stringResource(R.string.tts_script_badge),
                        container = LegadoTheme.colorScheme.secondaryContainer,
                        content = LegadoTheme.colorScheme.onSecondaryContainer,
                    )
                }
                summary.takeIf(String::isNotBlank)?.let {
                    VoiceTraitTag(
                        text = it,
                        container = LegadoTheme.colorScheme.surfaceVariant,
                        content = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VoiceTraitTag(
                    text = stringResource(R.string.cloud_tts_voice_count, voiceCount),
                    container = LegadoTheme.colorScheme.tertiaryContainer,
                    content = LegadoTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
        trailingContent = {
            AppActionMenu(
                groups = persistentListOf(
                    AppActionGroup(id = "engine_actions", actions = actions)
                ),
                onAction = { id ->
                    when (id) {
                        "set_default" -> onIntent(
                            CloudTtsIntent.SetDefaultEngine(engineKey.engineType, engineKey.engineId)
                        )

                        "edit" -> if (engineKey.engineType == ReadAloudVoice.ENGINE_CLOUD) {
                            onIntent(CloudTtsIntent.EditEngine(engineKey.engineId))
                        } else {
                            onIntent(CloudTtsIntent.EditHttpTts(engineKey.engineId))
                        }

                        "delete" -> if (engineKey.engineType == ReadAloudVoice.ENGINE_CLOUD) {
                            onIntent(CloudTtsIntent.DeleteEngine(engineKey.engineId))
                        } else {
                            onIntent(CloudTtsIntent.DeleteHttpTts(engineKey.engineId))
                        }

                        "login" -> onIntent(CloudTtsIntent.OpenHttpTtsLogin(engineKey.engineId))
                    }
                },
                anchor = AppMenuAnchor.Row,
                title = stringResource(R.string.more_menu),
            ) {
                MediumTonalButton(
                    onClick = null,
                    modifier = this.modifier,
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_menu),
                )
            }
        },
        onClick = {
            onIntent(CloudTtsIntent.OpenEngine(engineKey.engineType, engineKey.engineId))
        },
        onLongClick = {
            onIntent(CloudTtsIntent.SetDefaultEngine(engineKey.engineType, engineKey.engineId))
        },
    )
}

@Composable
private fun VoiceActionsMenu(
    voice: CloudTtsVoiceItemUi,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    val actions = persistentListOf(
        AppAction(
            id = "toggle_default_narrator",
            label = stringResource(
                if (voice.isDefaultNarrator) R.string.cloud_tts_clear_default_narrator
                else R.string.cloud_tts_set_default_narrator
            ),
            selected = voice.isDefaultNarrator,
        ),
        AppAction("edit", stringResource(R.string.edit)),
    )
    AppActionMenu(
        groups = persistentListOf(
            AppActionGroup(id = "voice_actions", actions = actions)
        ),
        onAction = { id ->
            when (id) {
                "toggle_default_narrator" ->
                    onIntent(CloudTtsIntent.ToggleDefaultNarratorVoice(voice.id))

                "edit" -> onIntent(CloudTtsIntent.EditVoice(voice.id))
            }
        },
        anchor = AppMenuAnchor.Row,
        title = stringResource(R.string.more_menu),
    ) {
        MediumTonalButton(
            onClick = null,
            modifier = this.modifier,
            icon = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.more_menu),
        )
    }
}

@Composable
private fun HttpTtsEditorSheet(
    value: HttpTTS?,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    var cachedSource by remember { mutableStateOf(value) }
    if (value != null) cachedSource = value
    val source = cachedSource ?: return
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(source) { mutableStateOf(source.name) }
    var url by remember(source) { mutableStateOf(source.url) }
    var script by remember(source) { mutableStateOf(source.script.orEmpty()) }
    var optionValues by remember(source) {
        mutableStateOf(
            runCatching {
                GSON.fromJsonObject<Map<String, String>>(source.scriptOptionsJson).getOrNull().orEmpty()
            }.getOrNull().orEmpty()
        )
    }
    var contentType by remember(source) { mutableStateOf(source.contentType.orEmpty()) }
    var concurrentRate by remember(source) { mutableStateOf(source.concurrentRate ?: "0") }
    var header by remember(source) { mutableStateOf(source.header.orEmpty()) }
    var loginUrl by remember(source) { mutableStateOf(source.loginUrl.orEmpty()) }
    var loginUi by remember(source) { mutableStateOf(source.loginUi.orEmpty()) }
    var loginCheckJs by remember(source) { mutableStateOf(source.loginCheckJs.orEmpty()) }
    var jsLib by remember(source) { mutableStateOf(source.jsLib.orEmpty()) }
    var speed by remember(source) { mutableStateOf((source.speed ?: 5).toFloat()) }

    fun current() = source.copy(
        name = name,
        url = url,
        script = script.ifBlank { null },
        scriptOptionsJson = if (script.isBlank()) "{}" else GSON.toJson(optionValues),
        contentType = contentType.ifBlank { null },
        concurrentRate = concurrentRate,
        header = header.ifBlank { null },
        loginUrl = loginUrl.ifBlank { null },
        loginUi = loginUi.ifBlank { null },
        loginCheckJs = loginCheckJs.ifBlank { null },
        jsLib = jsLib.ifBlank { null },
        speed = speed.toInt(),
    )

    AppModalBottomSheet(
        show = value != null,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissHttpTtsEditor) },
        title = stringResource(R.string.speak_engine),
        startSheetAction = SheetAction(
            icon = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.more_menu),
            menu = persistentListOf(
                SheetMenuAction(
                    id = "copy_text",
                    label = stringResource(R.string.copy_text),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        "httpTTS",
                                        GSON.toJson(current())
                                    )
                                )
                            )
                        }
                    },
                ),
                SheetMenuAction(
                    id = "paste_source",
                    label = stringResource(R.string.paste_source),
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData
                                ?.getItemAt(0)?.coerceToText(context)?.toString()
                                ?: return@launch
                            HttpTTS.fromJson(text).getOrNull()?.let { imported ->
                                name = imported.name
                                url = imported.url
                                script = imported.script.orEmpty()
                                optionValues = runCatching {
                                    GSON.fromJsonObject<Map<String, String>>(
                                        imported.scriptOptionsJson
                                    ).getOrNull().orEmpty()
                                }.getOrNull().orEmpty()
                                contentType = imported.contentType.orEmpty()
                                concurrentRate = imported.concurrentRate ?: "0"
                                header = imported.header.orEmpty()
                                loginUrl = imported.loginUrl.orEmpty()
                                loginUi = imported.loginUi.orEmpty()
                                loginCheckJs = imported.loginCheckJs.orEmpty()
                                jsLib = imported.jsLib.orEmpty()
                                speed = (imported.speed ?: 5).toFloat()
                            }
                        }
                    },
                ),
            ),
        ),
        endSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.SaveHttpTts(current())) },
            icon = Icons.Default.Save,
            contentDescription = stringResource(R.string.save),
        ),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(
                    name,
                    { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    script,
                    { script = it },
                    label = "引擎脚本 (JS)",
                    minLines = 6,
                    // 不设 maxLines 的话多行 TextField 会按整段脚本的行数测量，
                    // 几十 KB 的脚本直接顶破 Compose Constraints 上限（262143px）崩溃；
                    // 限制可视行数后内容走 TextField 内部滚动
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (script.isNotBlank()) {
                item {
                    ScriptOptionsForm(
                        source = source,
                        script = script,
                        optionValues = optionValues,
                        onOptionChange = { key, value ->
                            optionValues = optionValues + (key to value)
                        },
                    )
                }
            }
            item {
                AppTextField(
                    url,
                    { url = it },
                    label = "URL",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                TinySliderSettingItem(
                    title = stringResource(R.string.read_aloud_speed),
                    description = stringResource(R.string.tts_source_speed_summary),
                    value = speed,
                    valueRange = 0f..80f,
                    steps = 79,
                    onValueChange = { speed = it },
                )
            }
            item {
                AppTextField(
                    contentType,
                    { contentType = it },
                    label = "Content-Type",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    concurrentRate,
                    { concurrentRate = it },
                    label = stringResource(R.string.concurrent_rate),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    header,
                    { header = it },
                    label = stringResource(R.string.source_http_header),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginUrl,
                    { loginUrl = it },
                    label = stringResource(R.string.login_url),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginUi,
                    { loginUi = it },
                    label = stringResource(R.string.login_ui),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginCheckJs,
                    { loginCheckJs = it },
                    label = stringResource(R.string.login_check_js),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    jsLib,
                    { jsLib = it },
                    label = "jsLib",
                    minLines = 2,
                    // 同 script：长库文本不设上限会把测量高度顶破 Constraints
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * 脚本引擎 options() 动态配置表单：按配置项类型渲染输入控件，
 * 值收集进 [optionValues]（保存时序列化回 scriptOptionsJson）。
 */
@Composable
private fun ScriptOptionsForm(
    source: HttpTTS,
    script: String,
    optionValues: Map<String, String>,
    onOptionChange: (String, String) -> Unit,
) {
    var options by remember(script) { mutableStateOf<List<TtsScriptOption>?>(null) }
    LaunchedEffect(script) {
        options = withContext(Dispatchers.IO) {
            runCatching {
                TtsScriptEngineClient.loadOptions(source.copy(script = script))
            }.getOrNull().orEmpty()
        }
    }
    val loaded = options
    if (loaded == null || loaded.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText(
            text = stringResource(R.string.tts_script_options_title),
            fontWeight = FontWeight.SemiBold,
        )
        loaded.forEach { option ->
            ScriptOptionField(
                source = source,
                option = option,
                value = optionValues[option.key].orEmpty(),
                onChange = { onOptionChange(option.key, it) },
            )
        }
    }
}

@Composable
private fun ScriptOptionField(
    source: HttpTTS,
    option: TtsScriptOption,
    value: String,
    onChange: (String) -> Unit,
) {
    when (option.normalizedType) {
        TtsScriptOptionType.BOOLEAN -> TinySwitchSettingItem(
            title = option.label,
            checked = value == "true" || (value.isBlank() && option.defaultValue == "true"),
            onCheckedChange = { onChange(it.toString()) },
        )

        TtsScriptOptionType.SELECT -> ScriptSelectField(option, value, onChange)

        TtsScriptOptionType.RANDOM_NUMBER -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTextField(
                value,
                onChange,
                label = option.label,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            MediumTonalButton(
                onClick = {
                    onChange(
                        TtsRandomNumberStore.regenerate(
                            source.id.toString(),
                            option.key,
                            option.digits,
                            option.allowLeadingZero,
                        )
                    )
                },
                icon = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.regenerate),
            )
        }

        TtsScriptOptionType.PASSWORD -> AppTextField(
            value,
            onChange,
            label = option.label,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        TtsScriptOptionType.NUMBER -> AppTextField(
            value,
            onChange,
            label = option.label,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        else -> AppTextField(
            value,
            onChange,
            label = option.label,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScriptSelectField(
    option: TtsScriptOption,
    value: String,
    onChange: (String) -> Unit,
) {
    val choices = remember(option) {
        option.values.map { AppChoiceOption(it.value, it.label) }.toImmutableList()
    }
    val title = "${option.label}: ${value.ifBlank { "—" }}"
    AppChoiceDropdown(
        options = choices,
        selectedId = value,
        onSelect = onChange,
        title = title,
        modifier = Modifier.fillMaxWidth(),
        compact = true,
        triggerShape = IconButtonDefaults.extraSmallRoundShape,
    ) {
        MediumTonalButton(
            onClick = null,
            text = title,
            modifier = this.modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CloudTtsEngineEditorSheet(
    state: CloudTtsUiState,
    editor: CloudTtsEngineEditorUi?,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    AppModalBottomSheet(
        data = editor,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissEngineEditor) },
        title = stringResource(
            if (editor?.editingEngineId == null) R.string.cloud_tts_add_engine
            else R.string.cloud_tts_edit_engine
        ),
        startSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.DismissEngineEditor) },
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.cancel),
        ),
        endSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.Save) },
            icon = Icons.Default.Check,
            contentDescription = stringResource(R.string.save),
        ),
    ) { currentEditor ->
        CloudTtsEngineEditorContent(state, currentEditor, onIntent)
    }
}

@Composable
private fun CloudTtsEngineEditorContent(
    state: CloudTtsUiState,
    editor: CloudTtsEngineEditorUi,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    var customRegion by remember(editor.provider, editor.editingEngineId) {
        val options = CloudTtsProviderType.entries.firstOrNull {
            it.storageValue == editor.provider
        }?.profile?.regionOptions.orEmpty()
        mutableStateOf(editor.region.isNotBlank() && editor.region !in options)
    }
    fun update(value: CloudTtsEngineEditorUi) = onIntent(CloudTtsIntent.UpdateEngineEditor(value))
    val provider = CloudTtsProviderType.entries.firstOrNull { it.storageValue == editor.provider }
        ?: CloudTtsProviderType.Mimo
    val profile = provider.profile
    LazyColumn {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val providerChoices = remember {
                    CloudTtsProviderType.entries
                        .map { AppChoiceOption(it.storageValue, it.profile.displayName) }
                        .toImmutableList()
                }
                AppChoiceDropdown(
                    options = providerChoices,
                    selectedId = editor.provider,
                    onSelect = { providerValue ->
                        CloudTtsProviderType.entries
                            .firstOrNull { it.storageValue == providerValue }
                            ?.let { option ->
                                update(
                                    editor.copy(
                                        provider = option.storageValue,
                                        name = if (editor.name.isBlank() ||
                                            editor.name == profile.displayName
                                        ) {
                                            option.profile.displayName
                                        } else {
                                            editor.name
                                        },
                                        model = option.profile.defaultModel,
                                        region = option.profile.regionOptions.firstOrNull()
                                            .orEmpty(),
                                    )
                                )
                            }
                    },
                    title = profile.displayName,
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                    triggerShape = IconButtonDefaults.extraSmallRoundShape,
                ) {
                    MediumTonalButton(
                        onClick = null,
                        text = profile.displayName,
                        modifier = this.modifier.fillMaxWidth(),
                    )
                }
                Field(
                    editor.name,
                    { update(editor.copy(name = it)) },
                    stringResource(R.string.cloud_tts_engine_name)
                )
                Field(
                    editor.baseUrl,
                    { update(editor.copy(baseUrl = it)) },
                    stringResource(R.string.cloud_tts_custom_base_url)
                )
                Field(editor.apiKey, { update(editor.copy(apiKey = it)) }, profile.apiKeyLabel)
                if (provider == CloudTtsProviderType.AwsPolly) {
                    Field(
                        editor.secretKey,
                        { update(editor.copy(secretKey = it)) },
                        "Secret Access Key"
                    )
                }
                if (profile.regionOptions.isNotEmpty()) {
                    val regionTitle = stringResource(
                        R.string.cloud_tts_region_value,
                        editor.region.ifBlank { stringResource(R.string.cloud_tts_please_select) }
                    )
                    // 区域是预设单选与自定义命令的混合菜单，不能迁成纯单选。
                    AppActionMenu(
                        groups = persistentListOf(
                            AppActionGroup(
                                id = "cloud_tts_region",
                                actions = buildList {
                                    profile.regionOptions.forEach { region ->
                                        add(
                                            AppAction(
                                                id = "region_$region",
                                                label = region,
                                                checked = editor.region == region,
                                            )
                                        )
                                    }
                                    add(
                                        AppAction(
                                            id = "custom_region",
                                            label = stringResource(
                                                R.string.cloud_tts_custom_region_action
                                            ),
                                        )
                                    )
                                }.toImmutableList(),
                            )
                        ),
                        onAction = { id ->
                            if (id == "custom_region") {
                                customRegion = true
                                update(editor.copy(region = ""))
                            } else {
                                customRegion = false
                                update(editor.copy(region = id.removePrefix("region_")))
                            }
                        },
                        anchor = AppMenuAnchor.Row,
                        title = regionTitle,
                    ) {
                        MediumTonalButton(
                            onClick = null,
                            text = regionTitle,
                            modifier = this.modifier.fillMaxWidth(),
                        )
                    }
                    if (customRegion) {
                        Field(
                            editor.region,
                            { update(editor.copy(region = it)) },
                            stringResource(R.string.cloud_tts_custom_region)
                        )
                    }
                }
                if (provider == CloudTtsProviderType.Volcengine) {
                    Field(editor.appId, { update(editor.copy(appId = it)) }, "App ID")
                }
                if (profile.modelOptions.isNotEmpty()) {
                    val modelTitle = stringResource(
                        R.string.cloud_tts_model_value,
                        editor.model.ifBlank { "neural" }
                    )
                    val modelChoices = remember(profile) {
                        profile.modelOptions.map { AppChoiceOption(it, it) }.toImmutableList()
                    }
                    AppChoiceDropdown(
                        options = modelChoices,
                        selectedId = editor.model,
                        onSelect = { model -> update(editor.copy(model = model)) },
                        title = modelTitle,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                        triggerShape = IconButtonDefaults.extraSmallRoundShape,
                    ) {
                        MediumTonalButton(
                            onClick = null,
                            text = modelTitle,
                            modifier = this.modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Field(editor.model, { update(editor.copy(model = it)) }, profile.modelLabel)
                }
                if (provider == CloudTtsProviderType.Volcengine) {
                    Field(
                        editor.optionsJson,
                        { update(editor.copy(optionsJson = it)) },
                        stringResource(R.string.cloud_tts_options_json)
                    )
                }
                MediumTonalButton(
                    onClick = { onIntent(CloudTtsIntent.TestEngine) },
                    enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(if (state.testing) R.string.cloud_tts_testing else R.string.cloud_tts_connection_test),
                )
            }
        }
    }
}

@Composable
private fun TtsVoicePresetEditorSheet(
    state: CloudTtsUiState,
    editor: TtsVoicePresetEditorUi?,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    AppModalBottomSheet(
        data = editor,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissVoiceEditor) },
        title = stringResource(
            if (editor?.editingVoiceId == null) R.string.cloud_tts_add_voice
            else R.string.cloud_tts_edit_voice
        ),
        startSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.DismissVoiceEditor) },
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.cancel),
        ),
        endSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.Save) },
            icon = Icons.Default.Check,
            enabled = editor?.voiceId?.isNotBlank() == true,
            contentDescription = stringResource(R.string.save),
        ),
    ) { currentEditor ->
        TtsVoicePresetEditorContent(state, currentEditor, onIntent)
    }
}

@Composable
private fun TtsVoicePresetEditorContent(
    state: CloudTtsUiState,
    editor: TtsVoicePresetEditorUi,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    var voiceQuery by remember { mutableStateOf("") }
    var manualVoiceIdVisible by remember(editor.engineId) { mutableStateOf(false) }
    val selectedVoice = state.discoveredVoices.firstOrNull { it.id == editor.voiceId }
    val selectedEngine = state.availableEngines.firstOrNull {
        it.engineType == editor.engineType && it.engineId == editor.engineId
    }
    val filteredVoices = state.discoveredVoices.filter { voice ->
        voiceQuery.isBlank() || voice.label.contains(voiceQuery, ignoreCase = true) ||
            voice.id.contains(voiceQuery, ignoreCase = true)
    }
    fun update(value: TtsVoicePresetEditorUi) = onIntent(CloudTtsIntent.UpdateVoiceEditor(value))

    LazyColumn(
            Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (editor.engineId.isNotBlank()) {
                item {
                    EngineSectionTitle(R.string.cloud_tts_step_voice)
                    if (editor.editingVoiceId == null && state.discoveredVoices.isNotEmpty()) {
                        SearchBar(
                            query = voiceQuery,
                            onQueryChange = { voiceQuery = it },
                            placeholder = stringResource(R.string.cloud_tts_search_voice),
                            autoFocus = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    if (state.discovering) {
                        AppText(stringResource(R.string.cloud_tts_loading_voices), Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    } else if (editor.editingVoiceId == null && filteredVoices.isEmpty()) {
                        AppText(stringResource(R.string.cloud_tts_no_matching_voice), Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
                if (editor.editingVoiceId == null) {
                    // 同一引擎可以有多个同名音色（小米引擎的 Voice.name 就只有「zh」这种），
                    // 只用 id 当 key 会让 LazyColumn 直接崩溃
                    items(filteredVoices, key = { "${it.id}|${it.locale}" }) { voice ->
                        VoiceCardItem(
                            title = voice.label.substringBefore(" · "),
                            // 第一行：名字 + 语言/风格小标记
                            tags = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    voice.locale.takeIf(String::isNotBlank)?.let {
                                        VoiceTraitTag(
                                            text = it.take(6),
                                            container = LegadoTheme.colorScheme.tertiaryContainer,
                                            content = LegadoTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                    (voice.styles + voice.roles).distinct()
                                        .take(2)
                                        .forEach { tag ->
                                            VoiceTraitTag(
                                                text = tag,
                                                container = LegadoTheme.colorScheme.surfaceVariant,
                                                content = LegadoTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                }
                            },
                            // 第二行：目录标签彩块 + id 弱色块 + 选中标记，横向滑动
                            scrollableTags = {
                                Row(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    (voice.styles + voice.roles + voice.tags)
                                        .distinct()
                                        .take(5)
                                        .forEach { tag ->
                                            VoiceTraitTag(
                                                text = tag,
                                                container = LegadoTheme.colorScheme.secondaryContainer,
                                                content = LegadoTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    VoiceTraitTag(
                                        text = voice.id,
                                        container = LegadoTheme.colorScheme.surfaceVariant,
                                        content = LegadoTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (voice.id == editor.voiceId) {
                                        VoiceTraitTag(
                                            text = stringResource(R.string.cloud_tts_selected),
                                            container = LegadoTheme.colorScheme.primaryContainer,
                                            content = LegadoTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                MediumTonalButton(
                                    onClick = {
                                        onIntent(CloudTtsIntent.PreviewCandidateVoice(voice.id))
                                    },
                                    selected = state.previewingCandidateVoiceId == voice.id,
                                    icon = Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.voice_preview),
                                )
                            },
                            onClick = { onIntent(CloudTtsIntent.SelectVoice(voice.id)) },
                        )
                    }
                } else {
                    item {
                        TinyClickableSettingItem(
                            title = editor.voiceName.ifBlank { editor.voiceId },
                            description = "${editor.voiceId} | ${stringResource(R.string.cloud_tts_current_voice)}",
                            onClick = {},
                        )
                    }
                }
                item {
                    if (selectedEngine?.remoteCatalog == true) {
                        MediumTonalButton(
                            onClick = { onIntent(CloudTtsIntent.DiscoverVoices) },
                            enabled = !state.discovering,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            text = stringResource(R.string.cloud_tts_refresh_catalog),
                        )
                    }
                    if (editor.editingVoiceId == null && editor.engineType == ReadAloudVoice.ENGINE_CLOUD) {
                        MediumTonalButton(
                            onClick = { manualVoiceIdVisible = !manualVoiceIdVisible },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            text = stringResource(if (manualVoiceIdVisible) R.string.cloud_tts_hide_manual_voice else R.string.cloud_tts_manual_voice_action),
                        )
                    }
                    if (manualVoiceIdVisible && editor.editingVoiceId == null &&
                        editor.engineType == ReadAloudVoice.ENGINE_CLOUD) {
                        Field(
                            editor.voiceId,
                            { update(editor.copy(voiceId = it.trim())) },
                            stringResource(R.string.cloud_tts_native_voice_id),
                        )
                        AppText(
                            stringResource(R.string.cloud_tts_manual_voice_hint),
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EngineSectionTitle(R.string.cloud_tts_step_preset)
                    // 名称与标签对所有音色开放：目录同步音色改名走 MANAGED_BY_USER_NAMED，
                    // 引擎目录同步时保留用户起的名字
                    Field(editor.voiceName, { update(editor.copy(voiceName = it)) }, stringResource(R.string.cloud_tts_preset_name))
                    // 自定义标签备注：两种模式都可改
                    Field(
                        editor.labelTags,
                        { update(editor.copy(labelTags = it)) },
                        stringResource(R.string.voice_label_tags),
                    )
                    if (!editor.readonlyPreset) {
                        // 预设风格（下拉）：性别/语言自动来自引擎目录声明（对齐 NG，不再手动标注）
                        if (editor.engineType == ReadAloudVoice.ENGINE_CLOUD &&
                            !selectedVoice?.styles.isNullOrEmpty()
                        ) {
                            val defaultLabel = stringResource(R.string.cloud_tts_default)
                            val styleTitle = stringResource(
                                R.string.cloud_tts_style_value,
                                editor.style.ifBlank { defaultLabel }
                            )
                            val styleChoices = remember(selectedVoice, defaultLabel) {
                                buildList {
                                    add(AppChoiceOption("", defaultLabel))
                                    selectedVoice?.styles.orEmpty().forEach {
                                        add(AppChoiceOption(it, it))
                                    }
                                }.toImmutableList()
                            }
                            AppChoiceDropdown(
                                options = styleChoices,
                                selectedId = editor.style,
                                onSelect = { style -> update(editor.copy(style = style)) },
                                title = styleTitle,
                                modifier = Modifier.fillMaxWidth(),
                                compact = true,
                                triggerShape = IconButtonDefaults.extraSmallRoundShape,
                            ) {
                                MediumTonalButton(
                                    onClick = null,
                                    text = styleTitle,
                                    modifier = this.modifier.fillMaxWidth(),
                                )
                            }
                        }
                        // 声音调节：数值统一用一行一个滑块（0.5x..2x，0.25 档），不再三个挤一行
                        EngineSectionTitle(R.string.cloud_tts_section_tuning)
                        when (editor.engineType) {
                            ReadAloudVoice.ENGINE_CLOUD -> {
                                listOf(
                                    stringResource(R.string.cloud_tts_speed) to editor.speed,
                                    stringResource(R.string.cloud_tts_pitch) to editor.pitch,
                                    stringResource(R.string.cloud_tts_volume) to editor.volume,
                                ).forEachIndexed { index, (label, raw) ->
                                    TinySliderSettingItem(
                                        title = label,
                                        value = raw.toFloatOrNull() ?: 1f,
                                        valueRange = 0.5f..2f,
                                        steps = 5,
                                        onValueChange = { value ->
                                            update(
                                                when (index) {
                                                    0 -> editor.copy(speed = value.toString())
                                                    1 -> editor.copy(pitch = value.toString())
                                                    else -> editor.copy(volume = value.toString())
                                                }
                                            )
                                        },
                                    )
                                }
                                val formatTitle = stringResource(
                                    R.string.cloud_tts_audio_format,
                                    editor.format
                                )
                                val formatChoices = remember(editor.formatOptions) {
                                    editor.formatOptions
                                        .map { AppChoiceOption(it, it) }
                                        .toImmutableList()
                                }
                                AppChoiceDropdown(
                                    options = formatChoices,
                                    selectedId = editor.format,
                                    onSelect = { format -> update(editor.copy(format = format)) },
                                    title = formatTitle,
                                    modifier = Modifier.fillMaxWidth(),
                                    compact = true,
                                    triggerShape = IconButtonDefaults.extraSmallRoundShape,
                                ) {
                                    MediumTonalButton(
                                        onClick = null,
                                        text = formatTitle,
                                        modifier = this.modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            ReadAloudVoice.ENGINE_SYSTEM -> {
                                TinySliderSettingItem(
                                    title = stringResource(R.string.cloud_tts_speed_multiplier),
                                    value = editor.speed.toFloatOrNull() ?: 1f,
                                    valueRange = 0.5f..2f,
                                    steps = 5,
                                    onValueChange = { update(editor.copy(speed = it.toString())) },
                                )
                                TinySliderSettingItem(
                                    title = stringResource(R.string.cloud_tts_pitch_multiplier),
                                    value = editor.pitch.toFloatOrNull() ?: 1f,
                                    valueRange = 0.5f..2f,
                                    steps = 5,
                                    onValueChange = { update(editor.copy(pitch = it.toString())) },
                                )
                            }

                            // HTTP 脚本引擎：参数由脚本自理，只留试听
                            else -> Unit
                        }
                        // 试听：所有引擎同一位置、整行按钮，不再混在参数行里
                        MediumTonalButton(
                            onClick = { onIntent(CloudTtsIntent.Preview) },
                            enabled = editor.voiceId.isNotBlank() && !state.testing,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            text = stringResource(if (state.testing) R.string.cloud_tts_preview_generating else R.string.cloud_tts_preview),
                        )
                    }
                    if (editor.engineType == ReadAloudVoice.ENGINE_CLOUD && !editor.readonlyPreset) {
                        // 朗读表演：角色/指令与三个表演开关，都属于“念的时候怎么演”，跟参数分开一级
                        EngineSectionTitle(R.string.cloud_tts_section_performance)
                        if (!selectedVoice?.roles.isNullOrEmpty()) {
                            val defaultLabel = stringResource(R.string.cloud_tts_default)
                            val roleTitle = stringResource(
                                R.string.cloud_tts_role_value,
                                editor.role.ifBlank { defaultLabel }
                            )
                            val roleChoices = remember(selectedVoice, defaultLabel) {
                                buildList {
                                    add(AppChoiceOption("", defaultLabel))
                                    selectedVoice?.roles.orEmpty().forEach {
                                        add(AppChoiceOption(it, it))
                                    }
                                }.toImmutableList()
                            }
                            AppChoiceDropdown(
                                options = roleChoices,
                                selectedId = editor.role,
                                onSelect = { role -> update(editor.copy(role = role)) },
                                title = roleTitle,
                                modifier = Modifier.fillMaxWidth(),
                                compact = true,
                                triggerShape = IconButtonDefaults.extraSmallRoundShape,
                            ) {
                                MediumTonalButton(
                                    onClick = null,
                                    text = roleTitle,
                                    modifier = this.modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Field(editor.instructions, { update(editor.copy(instructions = it)) }, stringResource(R.string.cloud_tts_instructions))
                        TinySwitchSettingItem(
                            title = stringResource(R.string.cloud_tts_automatic_emotion),
                            description = stringResource(R.string.cloud_tts_automatic_emotion_summary),
                            checked = editor.automaticEmotion,
                            onCheckedChange = { update(editor.copy(automaticEmotion = it)) },
                        )
                        TinySwitchSettingItem(
                            title = stringResource(R.string.cloud_tts_character_personality),
                            description = stringResource(R.string.cloud_tts_character_personality_summary),
                            checked = editor.characterPersonality,
                            onCheckedChange = { update(editor.copy(characterPersonality = it)) },
                        )
                        TinySwitchSettingItem(
                            title = stringResource(R.string.cloud_tts_thought_performance),
                            description = stringResource(R.string.cloud_tts_thought_performance_summary),
                            checked = editor.thoughtPerformance,
                            onCheckedChange = { update(editor.copy(thoughtPerformance = it)) },
                        )
                    }
                }
                item {
                    // 声音池：下拉按钮自带「声音池：xxx」文案，不再单放分区标题
                    if (editor.memberPools.isEmpty()) {
                        AppText(
                            stringResource(R.string.voice_pool_empty),
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 下拉多选：按钮显示已加入的池；菜单项 ✓ 前缀切换、保持展开可连续选，
                        // 与本弹窗其它选择类控件同一形态，不再铺一排勾选行
                        var poolMenu by remember { mutableStateOf(false) }
                        val selectedPoolNames = editor.memberPools
                            .filter { it.checked }
                            .joinToString("、") { it.name }
                        val poolTitle = stringResource(
                            R.string.voice_pool_value,
                            selectedPoolNames.ifBlank { stringResource(R.string.voice_pool_none) },
                        )
                        AppAnchoredPanel(
                            expanded = poolMenu,
                            onExpandedChange = { poolMenu = it },
                            trigger = {
                                MediumTonalButton(
                                    onClick = null,
                                    text = poolTitle,
                                    modifier = this.modifier.fillMaxWidth(),
                                )
                            },
                        ) { _ ->
                            editor.memberPools.forEach { pool ->
                                RoundDropdownMenuItem(
                                    text = (if (pool.checked) "✓ " else "") + pool.name,
                                    isSelected = pool.checked,
                                    onClick = {
                                        update(
                                            editor.copy(
                                                memberPools = editor.memberPools.map {
                                                    if (it.poolId == pool.poolId) {
                                                        it.copy(checked = !it.checked)
                                                    } else it
                                                }.toPersistentList(),
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (editor.editingVoiceId != null) {
                        // 自建预设＝删除；目录同步音色＝「不再显示」（置 excluded，列表可恢复）
                        MediumTonalButton(
                            onClick = {
                                val voiceId = editor.editingVoiceId.orEmpty()
                                onIntent(CloudTtsIntent.DismissVoiceEditor)
                                if (editor.readonlyPreset) {
                                    onIntent(CloudTtsIntent.RequestExcludeVoice(voiceId))
                                } else {
                                    onIntent(CloudTtsIntent.RequestDeleteVoice(voiceId))
                                }
                            },
                            icon = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            text = stringResource(
                                if (editor.readonlyPreset) R.string.voice_preset_exclude
                                else R.string.voice_preset_delete
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        singleLine = true,
    )
}

@Composable
private fun VoiceEnginePickerSheet(
    show: Boolean,
    state: CloudTtsUiState,
    onIntent: (CloudTtsIntent) -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = { onIntent(CloudTtsIntent.DismissVoiceEnginePicker) },
        title = stringResource(R.string.cloud_tts_select_engine),
        startSheetAction = SheetAction(
            onClick = { onIntent(CloudTtsIntent.DismissVoiceEnginePicker) },
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.cancel),
        ),
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            items(
                items = state.availableEngines,
                key = { "picker:${it.engineType}:${it.engineId}" },
            ) { engine ->
                TinyClickableSettingItem(
                    title = engine.title,
                    description = listOf(engine.summary, engine.catalogHint)
                        .filter(String::isNotBlank)
                        .joinToString(" | "),
                    onClick = {
                        onIntent(CloudTtsIntent.AddVoiceForEngine(engine.engineType, engine.engineId))
                    },
                )
            }
        }
    }
}

/**
 * 试听文本悬浮窗：输入框里是当前的默认试听文本，右下角「确定」保存并关闭，
 * 「试听」直接念输入框里的内容（不保存），方便一边改一边听。
 */
@Composable
private fun VoicePreviewTextDialog(
    voice: CloudTtsVoiceItemUi?,
    initialText: String,
    onDismiss: () -> Unit,
    onPreview: (CloudTtsVoiceItemUi, String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 常驻组合：voice 置 null 后仍留在组合里，缓存最后一份只是为了退场期间标题不空掉
    var cached by remember { mutableStateOf(voice) }
    if (voice != null) cached = voice
    val current = cached ?: return
    var text by remember(current.id) { mutableStateOf(initialText) }
    AppAlertDialog(
        show = voice != null,
        onDismissRequest = onDismiss,
        title = current.title,
        text = stringResource(R.string.cloud_tts_preview_text_summary),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    text,
                    { text = it },
                    label = stringResource(R.string.cloud_tts_preview_text_title),
                    minLines = 3,
                    // 弹层里的输入框用 onSheetContent 底色（半透明），与弹层底色分层
                    backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                    modifier = Modifier.fillMaxWidth(),
                )
                MediumTonalButton(
                    onClick = { onPreview(current, text) },
                    text = stringResource(R.string.cloud_tts_preview),
                    icon = Icons.Default.PlayArrow,
                )
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onConfirm(text) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
    )
}
