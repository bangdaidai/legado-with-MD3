package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.repository.ReadPreferences
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.usecase.BookmarkTargetVerdict
import io.legado.app.ui.book.read.sheet.AiRewritePresetConfigSheet
import io.legado.app.ui.book.read.sheet.AiTextCleanSheet
import io.legado.app.ui.book.read.sheet.AiTextRewriteSheet
import io.legado.app.ui.book.read.sheet.BgTextConfigSheet
import io.legado.app.ui.book.read.sheet.ChangeChapterSourceSheet
import io.legado.app.ui.book.read.sheet.ChapterSummarySheet
import io.legado.app.ui.book.read.sheet.CharsetConfigSheet
import io.legado.app.ui.book.read.sheet.ClickActionConfigSheet
import io.legado.app.ui.book.read.sheet.ContentEditSheet
import io.legado.app.ui.book.read.sheet.DownloadSheet
import io.legado.app.ui.book.read.sheet.EyeProtectionConfigSheet
import io.legado.app.ui.book.read.sheet.FloatingBarIconConfigSheet
import io.legado.app.ui.book.read.sheet.HighlightRuleConfigSheet
import io.legado.app.ui.book.read.sheet.MarkingSheet
import io.legado.app.ui.book.read.sheet.MarkingNotePopup
import io.legado.app.ui.book.read.sheet.MoreConfigSheet
import io.legado.app.ui.book.read.sheet.PageAnimConfigSheet
import io.legado.app.ui.book.read.sheet.PageKeyConfigSheet
import io.legado.app.ui.book.read.sheet.PhotoSheet
import io.legado.app.ui.book.read.sheet.ReaderMoreActionsSheet
import io.legado.app.ui.book.read.sheet.ShadowSetSheet
import io.legado.app.ui.book.read.sheet.SimulatedReadingSheet
import io.legado.app.ui.book.read.sheet.TextProcessingSheet
import io.legado.app.ui.book.read.sheet.TitleBarActionConfigSheet
import io.legado.app.ui.book.read.sheet.ToolButtonConfigSheet
import io.legado.app.ui.book.read.sheet.UnderlineConfigSheet
import io.legado.app.ui.dict.DictSheet
import io.legado.app.ui.book.knowledge.CharacterQuerySheet
import io.legado.app.ui.widget.shareCard.ShareCardPreviewSheet
import io.legado.app.ui.widget.components.FontFolderState
import io.legado.app.ui.widget.components.FontSelectSheet
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.bookmark.BookmarkEditSheet
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.changeSource.ChangeSourceSheet
import io.legado.app.ui.widget.components.checkBox.CheckboxItem
import io.legado.app.ui.widget.components.log.AppLogSheet
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest

/**
 * Stateless reader overlays: back handling, dialogs and sheets.
 * [ReadBookRouteScreen] owns the Compose Canvas reading surface.
 */
@Composable
fun ReadBookOverlayRoute(
    viewModel: ReadBookViewModel,
    state: ReadBookUiState,
    preferences: ReadPreferences,
    onOpenTextSelectMenuConfig: () -> Unit,
    onPickBookmarkBadgeImage: () -> Unit,
    onResetBookmarkBadge: () -> Unit,
    sheetsVisible: Boolean = true,
    additionalSheetContent: @Composable (ReadBookSheet, Boolean, () -> Unit) -> Unit = { _, _, _ -> },
) {
    val aiActive = rememberFeatureActivated(
        state.findSheet<ReadBookSheet.ChapterSummary>() != null ||
                state.findSheet<ReadBookSheet.AiTextClean>() != null ||
                state.findSheet<ReadBookSheet.AiTextRewrite>() != null ||
                state.findSheet<ReadBookSheet.AiRewritePresetConfig>() != null
    )
    val highlightActive = rememberFeatureActivated(
        state.findSheet<ReadBookSheet.HighlightRuleConfig>() != null
    )
    val markingActive = rememberFeatureActivated(state.findSheet<ReadBookSheet.Marking>() != null)
    val contentEditActive =
        rememberFeatureActivated(state.findSheet<ReadBookSheet.ContentEdit>() != null)
    val contentProcessActive = rememberFeatureActivated(
        state.findSheet<ReadBookSheet.TextProcessing>() != null
    )
    val aiState = if (aiActive) {
        viewModel.aiState.collectAsStateWithLifecycle().value
    } else ReadAiUiState()
    val highlightRuleState = if (highlightActive) {
        viewModel.highlightRuleState.collectAsStateWithLifecycle().value
    } else HighlightRuleConfigUiState()
    val markingState = if (markingActive) {
        viewModel.markingState.collectAsStateWithLifecycle().value
    } else MarkingUiState()
    // 备注浮窗独立于笔记弹层：不跟着 markingActive 门控，否则「弹层没开」时它也收集不到。
    val markingNotePreview = viewModel.markingNotePreview.collectAsStateWithLifecycle().value
    val contentEditState = if (contentEditActive) {
        viewModel.contentEditState.collectAsStateWithLifecycle().value
    } else ContentEditUiState()
    val contentProcessState = if (contentProcessActive) {
        viewModel.contentProcessState.collectAsStateWithLifecycle().value
    } else ContentProcessConfigUiState()
    ReadBookScreen(
        state = state,
        sheetsVisible = sheetsVisible,
        additionalSheetContent = additionalSheetContent,
        aiState = aiState,
        highlightRuleState = highlightRuleState,
        markingState = markingState,
        markingNotePreview = markingNotePreview,
        contentEditState = contentEditState,
        contentProcessState = contentProcessState,
        preferences = preferences,
        onIntent = viewModel::onIntent,
        onOpenTextSelectMenuConfig = onOpenTextSelectMenuConfig,
        onPickBookmarkBadgeImage = onPickBookmarkBadgeImage,
        onResetBookmarkBadge = onResetBookmarkBadge,
    )
}

@Composable
private fun rememberFeatureActivated(active: Boolean): Boolean {
    var activated by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) activated = true
    }
    return active || activated
}

@Composable
fun ReadBookScreen(
    state: ReadBookUiState,
    sheetsVisible: Boolean = true,
    additionalSheetContent: @Composable (ReadBookSheet, Boolean, () -> Unit) -> Unit = { _, _, _ -> },
    aiState: ReadAiUiState,
    highlightRuleState: HighlightRuleConfigUiState,
    markingState: MarkingUiState,
    markingNotePreview: MarkingNotePreview?,
    contentEditState: ContentEditUiState,
    contentProcessState: ContentProcessConfigUiState,
    preferences: ReadPreferences,
    onIntent: (ReadBookIntent) -> Unit,
    onOpenTextSelectMenuConfig: () -> Unit,
    onPickBookmarkBadgeImage: () -> Unit,
    onResetBookmarkBadge: () -> Unit,
) {
    // Dialogs driven by activeDialog state
    val restoreDialog = state.activeDialog as? ReadBookDialog.ConfirmRestoreProgress
    val syncDialog = state.activeDialog as? ReadBookDialog.SureSyncProgress
    val restoreLastProgressDialog = state.activeDialog as? ReadBookDialog.RestoreLastBookProgress
    val skipDialog = state.activeDialog as? ReadBookDialog.ConfirmSkipToChapter
    val payDialog = state.activeDialog as? ReadBookDialog.ConfirmChapterPay
    val addToBookshelfDialog = state.activeDialog as? ReadBookDialog.ConfirmAddToBookshelf
    val readRecordAliasDialog = state.activeDialog as? ReadBookDialog.ReadRecordAliasConflict
    var rememberAliasChoice by remember(readRecordAliasDialog) { mutableStateOf(false) }

    AppAlertDialog(
        show = readRecordAliasDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.ResolveReadRecordAlias(false, rememberAliasChoice)) },
        title = stringResource(R.string.read_record_alias_title),
        text = readRecordAliasDialog?.let {
            stringResource(R.string.read_record_alias_message, it.bookName, it.readTime / 60000, it.author)
        }.orEmpty(),
        confirmText = stringResource(R.string.read_record_alias_merge),
        onConfirm = { onIntent(ReadBookIntent.ResolveReadRecordAlias(true, rememberAliasChoice)) },
        dismissText = stringResource(R.string.read_record_alias_keep_separate),
        onDismiss = { onIntent(ReadBookIntent.ResolveReadRecordAlias(false, rememberAliasChoice)) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                CheckboxItem(
                    title = stringResource(R.string.read_record_alias_remember),
                    checked = rememberAliasChoice,
                    onCheckedChange = { rememberAliasChoice = it },
                )
                MediumPlainButton(
                    onClick = {
                        onIntent(ReadBookIntent.ClearReadRecordAliasDecisions)
                    },
                    modifier = Modifier.align(Alignment.End),
                    text = stringResource(R.string.read_record_alias_revoke),
                )
            }
        },
    )

    AppAlertDialog(
        show = restoreDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.DismissDialog) },
        title = stringResource(R.string.restore_progress),
        text = stringResource(R.string.found_cloud_progress),
        confirmText = stringResource(R.string.ok),
        onConfirm = {
            restoreDialog?.let { onIntent(ReadBookIntent.SureNewProgress(it.progress)) }
            onIntent(ReadBookIntent.DismissDialog)
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissDialog) },
    )
    AppAlertDialog(
        show = syncDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.DismissDialog) },
        title = stringResource(R.string.sync_progress),
        text = stringResource(R.string.progress_exceeds_cloud),
        confirmText = stringResource(R.string.ok),
        onConfirm = {
            syncDialog?.let { onIntent(ReadBookIntent.SureSyncProgress(it.progress)) }
            onIntent(ReadBookIntent.DismissDialog)
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissDialog) },
    )
    AppAlertDialog(
        show = restoreLastProgressDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.KeepCurrentBookProgress) },
        title = stringResource(R.string.draw),
        text = stringResource(R.string.restore_last_book_process),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(ReadBookIntent.RestoreLastBookProgress) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.KeepCurrentBookProgress) },
    )
    AppAlertDialog(
        show = skipDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.DismissDialog) },
        title = stringResource(R.string.chapter_list),
        text = stringResource(R.string.confirm_skip_to_chapter),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(ReadBookIntent.DismissDialog) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissDialog) },
    )
    AppAlertDialog(
        show = payDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.DismissDialog) },
        title = stringResource(R.string.chapter_pay),
        text = payDialog?.chapterTitle ?: "",
        confirmText = stringResource(R.string.ok),
        onConfirm = {
            onIntent(ReadBookIntent.DismissDialog)
            onIntent(ReadBookIntent.ConfirmPayAction)
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissDialog) },
    )
    AppAlertDialog(
        show = addToBookshelfDialog != null,
        onDismissRequest = { onIntent(ReadBookIntent.ExitWithoutAddingCurrentBookToBookshelf) },
        title = stringResource(R.string.add_to_bookshelf),
        text = stringResource(
            R.string.check_add_bookshelf,
            addToBookshelfDialog?.bookName.orEmpty()
        ),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(ReadBookIntent.ConfirmAddCurrentBookToBookshelf) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.ExitWithoutAddingCurrentBookToBookshelf) },
    )

    // 书签/笔记跳转前校验未通过的确认框
    val pendingTarget = state.pendingBookmarkTarget
    AppAlertDialog(
        show = pendingTarget != null,
        onDismissRequest = { onIntent(ReadBookIntent.CancelBookmarkTargetJump) },
        title = stringResource(R.string.bookmark_target_may_shift),
        text = stringResource(
            when (pendingTarget?.verdict) {
                is BookmarkTargetVerdict.SourceChanged ->
                    R.string.bookmark_target_source_changed

                BookmarkTargetVerdict.TitleMismatch ->
                    R.string.bookmark_target_title_mismatch

                null -> R.string.bookmark_target_title_mismatch
                BookmarkTargetVerdict.Match -> R.string.bookmark_target_title_mismatch
            }
        ),
        confirmText = stringResource(R.string.bookmark_target_jump_anyway),
        onConfirm = { onIntent(ReadBookIntent.ConfirmBookmarkTargetJump) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.CancelBookmarkTargetJump) },
    )

    // Keep each layer keyed and mounted until its own sheet exit animation has finished.
    // Native modal windows must be created in stack order, including after recreation.
    val layers = remember { androidx.compose.runtime.mutableStateListOf<ReaderSheetLayer>() }
    var nextLayerId by rememberSaveable { mutableStateOf(0L) }
    var activeLayerIds by rememberSaveable { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(state.sheetStack) {
        val previous = layers.filter { it.present }
        val nextIds = mutableMapOf<String, Long>()
        layers.indices.forEach { index -> layers[index] = layers[index].copy(present = false) }
        state.sheetStack.forEach { sheet ->
            val kind = checkNotNull(sheet::class.qualifiedName)
            val retained = previous.find { it.sheet::class == sheet::class }
            if (retained == null) {
                val id = activeLayerIds[kind] ?: nextLayerId++
                layers.add(ReaderSheetLayer(id, sheet, true))
                nextIds[kind] = id
            } else {
                val index = layers.indexOfFirst { it.id == retained.id }
                layers[index] = retained.copy(sheet = sheet, present = true)
                nextIds[kind] = retained.id
            }
        }
        activeLayerIds = nextIds.toMap()
    }
    layers.forEach { layer ->
        androidx.compose.runtime.key(layer.id) {
            val alive = io.legado.app.ui.book.read.sheet.rememberSheetAlive(
                layer.present,
                exitMillis = 600L
            )
            if (alive) {
                val visible = layer.present && sheetsVisible
                val layerIntent: (ReadBookIntent) -> Unit = { intent ->
                    if (layer.present) {
                        onIntent(
                            if (intent == ReadBookIntent.DismissSheet ||
                                intent == ReadBookIntent.SetActiveSheet(null)
                            ) ReadBookIntent.DismissSheetIfTop(layer.sheet) else intent
                        )
                    }
                }
                ReadBookSheetContent(
                    state = state.copy(
                        sheetStack = kotlinx.collections.immutable.persistentListOf(
                            layer.sheet
                        )
                    ),
                    sheetsVisible = visible,
                    aiState = aiState,
                    highlightRuleState = highlightRuleState,
                    markingState = markingState,
                    contentEditState = contentEditState,
                    contentProcessState = contentProcessState,
                    preferences = preferences,
                    onIntent = layerIntent,
                    onOpenTextSelectMenuConfig = onOpenTextSelectMenuConfig,
                    onPickBookmarkBadgeImage = onPickBookmarkBadgeImage,
                    onResetBookmarkBadge = onResetBookmarkBadge,
                    markingNotePreview = markingNotePreview,
                )
                additionalSheetContent(layer.sheet, visible) {
                    layerIntent(ReadBookIntent.DismissSheet)
                }
            } else {
                androidx.compose.runtime.SideEffect { layers.removeAll { it.id == layer.id } }
            }
        }
    }
}

@androidx.compose.runtime.Stable
private data class ReaderSheetLayer(
    val id: Long,
    val sheet: ReadBookSheet,
    val present: Boolean,
)

@Composable
private fun ReadBookSheetContent(
    state: ReadBookUiState,
    sheetsVisible: Boolean,
    aiState: ReadAiUiState,
    highlightRuleState: HighlightRuleConfigUiState,
    markingState: MarkingUiState,
    contentEditState: ContentEditUiState,
    contentProcessState: ContentProcessConfigUiState,
    preferences: ReadPreferences,
    onIntent: (ReadBookIntent) -> Unit,
    onOpenTextSelectMenuConfig: () -> Unit,
    onPickBookmarkBadgeImage: () -> Unit,
    onResetBookmarkBadge: () -> Unit,
    markingNotePreview: MarkingNotePreview?,
) {
    val visibleState =
        if (sheetsVisible) state else state.copy(sheetStack = kotlinx.collections.immutable.persistentListOf())

    // Each mounted layer composes its own sheet; show controls its enter/exit animation.
    fun dismissSheet(sheet: ReadBookSheet? = state.activeSheet): () -> Unit = {
        sheet?.let { onIntent(ReadBookIntent.DismissSheetIfTop(it)) }
    }

    // 多角色分配弹层（点击正文角色胶囊打开）
    if (state.activeSheet is ReadBookSheet.RoleCast) {
        io.legado.app.ui.book.read.sheet.ReadAloudCastSheet(
            show = visibleState.findSheet<ReadBookSheet.RoleCast>() != null,
            ordinal = visibleState.findSheet<ReadBookSheet.RoleCast>()?.ordinal ?: -1,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.RoleCast>()),
            onIntent = onIntent,
            menuConfig = state.menuConfig,
        )
    }

    // 段首配乐弹层（点击正文 ♪ 胶囊打开）
    if (state.activeSheet is ReadBookSheet.BgmScene) {
        io.legado.app.ui.book.read.sheet.BgmSceneSheet(
            show = visibleState.findSheet<ReadBookSheet.BgmScene>() != null,
            paragraphIndex = visibleState.findSheet<ReadBookSheet.BgmScene>()?.paragraphIndex ?: -1,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.BgmScene>()),
            onIntent = onIntent,
            menuConfig = state.menuConfig,
        )
    }

    // 本章背景音乐总览（配乐弹层/朗读面板打开，一屏改完本章所有区间）
    if (state.activeSheet is ReadBookSheet.BgmSceneTable) {
        io.legado.app.ui.book.read.sheet.BgmSceneTableSheet(
            show = visibleState.findSheet<ReadBookSheet.BgmSceneTable>() != null,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.BgmSceneTable>()),
            onIntent = onIntent,
        )
    }

    // AI 识别配乐场景弹层（角色那一趟已随「AI 分配角色」入口退役）
    io.legado.app.ui.book.read.sheet.AiCastDialogSheet(
        show = state.activeSheet is ReadBookSheet.AiCastDialog,
        // 推理强度的唯一真相在朗读设置，这里只把当前值交给弹层回显
        reasoningLevel = AiReasoningLevel.fromStorage(
            state.speechAnalysisReasoningLevel,
            AiReasoningLevel.OFF,
        ),
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
    )
    // 听书下载悬浮窗：和其余卡片一样常挂，show 由 sheet 类型驱动，退场动画才有地方播
    // （上游 aef93c63b 重写了这份弹层，不再接收 menuConfig；菜单级开关仍由入口项控制）
    io.legado.app.ui.book.read.sheet.ReaderAudioDownloadSheet(
        show = state.activeSheet is ReadBookSheet.AudioDownload,
        onDismissRequest = dismissSheet(),
    )

    if (state.activeSheet is ReadBookSheet.ShadowSet) {
        ShadowSetSheet(
            show = visibleState.findSheet<ReadBookSheet.ShadowSet>() != null,
            config = state.sheetConfig,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.ShadowSet>()),
            onIntent = onIntent,
        )
    }
    if (state.activeSheet is ReadBookSheet.TextProcessing) {
        TextProcessingSheet(
            show = visibleState.findSheet<ReadBookSheet.TextProcessing>() != null,
            book = state.book,
            allRules = state.allReplaceRules,
            effectiveRules = state.effectiveReplaceRules,
            replaceEnabled = state.useReplaceRule,
            contentProcessState = contentProcessState,
            onIntent = onIntent,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.TextProcessing>()),
        )
    }
    if (state.activeSheet is ReadBookSheet.MoreActions) {
        ReaderMoreActionsSheet(
            show = visibleState.findSheet<ReadBookSheet.MoreActions>() != null,
            state = state,
            onIntent = onIntent,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.MoreActions>()),
        )
    }
    if (state.activeSheet is ReadBookSheet.UnderlineConfig) {
        UnderlineConfigSheet(
            show = visibleState.findSheet<ReadBookSheet.UnderlineConfig>() != null,
            config = state.sheetConfig,
            onDismissRequest = dismissSheet(visibleState.findSheet<ReadBookSheet.UnderlineConfig>()),
            onIntent = onIntent,
        )
    }
    val fontSelectFolderState = remember(preferences.fontFolder) {
        FontFolderState.Loaded(preferences.fontFolder.takeIf { it.isNotEmpty() }?.toUri())
    }
    val fontSelectSystemTypefaces = stringArrayResource(R.array.system_typefaces)
    FontSelectSheet(
        show = state.activeSheet is ReadBookSheet.FontSelect,
        title = stringResource(R.string.select_font),
        folderState = fontSelectFolderState,
        selectedFontPath = state.styleConfig.textFont,
        onDismissRequest = dismissSheet(),
        onSelectFont = { onIntent(ReadBookIntent.SelectFont(it.uri.toString())) },
        onSelectSystemTypeface = { onIntent(ReadBookIntent.SelectSystemTypeface(it)) },
        onOpenFolderPicker = { onIntent(ReadBookIntent.OpenFontFolderPicker) },
        systemTypefaces = fontSelectSystemTypefaces,
    )
    FontSelectSheet(
        show = state.activeSheet is ReadBookSheet.TitleFontSelect,
        title = stringResource(R.string.read_config_title_settings),
        folderState = fontSelectFolderState,
        selectedFontPath = state.styleConfig.titleFont,
        onDismissRequest = dismissSheet(),
        onSelectFont = { onIntent(ReadBookIntent.SelectTitleFont(it.uri.toString())) },
        onSelectSystemTypeface = { onIntent(ReadBookIntent.SelectTitleSystemTypeface(it)) },
        onOpenFolderPicker = { onIntent(ReadBookIntent.OpenFontFolderPicker) },
        systemTypefaces = fontSelectSystemTypefaces,
    )
    ToolButtonConfigSheet(
        show = state.activeSheet is ReadBookSheet.ToolButtonConfig,
        items = state.menuConfig.bottomBarButtons,
        customIcons = state.menuConfig.readMenuCustomIcons,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
    )
    EyeProtectionConfigSheet(
        show = state.activeSheet is ReadBookSheet.EyeProtection,
        enabled = state.eyeProtection.enabled,
        intensity = state.eyeProtection.intensity,
        autoNight = state.eyeProtection.autoNight,
        schedule = state.eyeProtection.schedule,
        startTime = state.eyeProtection.startTime,
        endTime = state.eyeProtection.endTime,
        onDismissRequest = dismissSheet(),
        onEnabledChange = { onIntent(ReadBookIntent.EyeProtectionEnabledChanged(it)) },
        onIntensityChange = { onIntent(ReadBookIntent.EyeProtectionIntensityChanged(it)) },
        onAutoNightChange = { onIntent(ReadBookIntent.EyeProtectionAutoNightChanged(it)) },
        onScheduleChange = { onIntent(ReadBookIntent.EyeProtectionScheduleChanged(it)) },
        onStartTimeChange = { onIntent(ReadBookIntent.EyeProtectionStartTimeChanged(it)) },
        onEndTimeChange = { onIntent(ReadBookIntent.EyeProtectionEndTimeChanged(it)) },
    )
    FloatingBarIconConfigSheet(
        show = state.activeSheet is ReadBookSheet.FloatingBarIconConfig,
        items = state.menuConfig.titleBarButtons,
        customIcons = state.menuConfig.titleBarCustomIcons,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
    )
    TitleBarActionConfigSheet(
        show = state.activeSheet is ReadBookSheet.TitleBarActionConfig,
        items = state.menuConfig.titleBarActions,
        customIcons = state.menuConfig.titleBarCustomIcons,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
    )
    HighlightRuleConfigSheet(
        show = state.activeSheet is ReadBookSheet.HighlightRuleConfig,
        state = highlightRuleState,
        allConfigNames = state.sheetConfig.configNames,
        config = state.sheetConfig,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
    )
    MarkingSheet(
        show = state.activeSheet is ReadBookSheet.Marking,
        state = markingState,
        onDismissRequest = { onIntent(ReadBookIntent.DismissMarking) },
        onSave = { style, note ->
            onIntent(ReadBookIntent.SaveMarking(style, note))
        },
        onDelete = { onIntent(ReadBookIntent.DeleteMarking) },
        onStylePreview = { style ->
            onIntent(ReadBookIntent.MarkingStylePreview(style))
        },
        onGenerateShareCard = if (markingState.editing != null) {
            { onIntent(ReadBookIntent.GenerateShareCardFromMarking) }
        } else {
            null
        },
    )
    // 备注浮窗：点角标打开（只读）；点浮窗里的备注则进笔记弹层改线型/颜色/备注，
    // 锚点沿用角标，弹层贴着角标浮出来、正文实时预览不被挡住。
    markingNotePreview?.let { preview ->
        MarkingNotePopup(
            anchor = preview.anchor,
            note = preview.note,
            onEditNote = {
                // EditMarking 会顺带收起只读浮窗（openForEdit 开头就 dismissNote），
                // 不必先发一次 DismissMarkingNote，否则中间会多一帧空窗。
                onIntent(ReadBookIntent.EditMarking(preview.markingId, preview.anchor))
            },
            onDismissRequest = { onIntent(ReadBookIntent.DismissMarkingNote) },
        )
    }
    ContentEditSheet(
        show = state.activeSheet is ReadBookSheet.ContentEdit,
        state = contentEditState,
        onIntent = onIntent,
        onDismissRequest = dismissSheet(),
    )
    ChapterSummarySheet(
        show = state.activeSheet is ReadBookSheet.ChapterSummary,
        state = aiState.chapterSummary,
        onIntent = onIntent,
        onDismissRequest = dismissSheet(),
    )
    AiTextCleanSheet(
        show = state.activeSheet is ReadBookSheet.AiTextClean,
        state = aiState.aiTextClean,
        onIntent = onIntent,
        onDismissRequest = dismissSheet(),
    )
    AiTextRewriteSheet(
        show = state.activeSheet is ReadBookSheet.AiTextRewrite,
        state = aiState.aiTextRewrite,
        onIntent = onIntent,
        onDismissRequest = dismissSheet(),
    )
    AiRewritePresetConfigSheet(
        show = state.activeSheet is ReadBookSheet.AiRewritePresetConfig,
        state = aiState.aiRewritePresetConfig,
        onIntent = onIntent,
        onDismissRequest = { onIntent(ReadBookIntent.CloseAiRewritePresetConfig) },
    )
    MoreConfigSheet(
        show = state.activeSheet is ReadBookSheet.MoreConfig,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
        onOpenClickRegionalConfig = {
            onIntent(ReadBookIntent.DismissSheet)
            onIntent(ReadBookIntent.ShowSheet(ReadBookSheet.ClickActionConfig))
        },
        onOpenPageKeyConfig = {
            onIntent(ReadBookIntent.DismissSheet)
            onIntent(ReadBookIntent.ShowSheet(ReadBookSheet.PageKeyConfig))
        },
        onOpenTextSelectMenuConfig = onOpenTextSelectMenuConfig,
        onPickBookmarkBadgeImage = onPickBookmarkBadgeImage,
        onResetBookmarkBadge = onResetBookmarkBadge,
    )
    AppLogSheet(
        show = state.activeSheet is ReadBookSheet.AppLog,
        onDismissRequest = dismissSheet(),
    )
    BgTextConfigSheet(
        show = state.activeSheet is ReadBookSheet.BgTextConfig,
        onDismissRequest = dismissSheet(),
        onIntent = onIntent,
        onSelectImage = { onIntent(ReadBookIntent.OpenReadStyleImagePicker) },
        onSelectImageForMode = { isNight ->
            onIntent(ReadBookIntent.OpenReadStyleImagePickerForMode(isNight))
        },
        onImportConfig = { onIntent(ReadBookIntent.OpenReadStyleImport) },
        onExportConfig = { onIntent(ReadBookIntent.OpenReadStyleExport) },
        styleConfig = state.styleConfig,
    )
    ShareCardPreviewSheet(
        show = state.showShareCard,
        data = state.shareCardData,
        loading = state.shareCardLoading,
        onDismissRequest = { onIntent(ReadBookIntent.DismissShareCard) },
        scene = state.shareCardScene,
    )

    // 朗读设置已改为 Navigation 3 整页（ReadAloudConfigScreen），阅读页不再挂弹层宿主；
    // 听书播放浮层的设置弹窗由 ReadAloudPlayerMorphHost 自己承载。

    val dictSheet = state.activeSheet as? ReadBookSheet.Dict
    DictSheet(
        show = dictSheet != null,
        word = dictSheet?.word ?: "",
        onDismissRequest = dismissSheet(),
    )
    val characterQuerySheet = state.activeSheet as? ReadBookSheet.CharacterQuery
    CharacterQuerySheet(
        show = characterQuerySheet != null,
        name = characterQuerySheet?.name ?: "",
        onDismissRequest = dismissSheet(),
        onJumpToChapter = { chapterIndex ->
            onIntent(ReadBookIntent.DismissSheet)
            onIntent(ReadBookIntent.OpenChapterResult(chapterIndex, 0))
        },
    )
    val photoSheet = state.activeSheet as? ReadBookSheet.Photo
    PhotoSheet(
        show = photoSheet != null,
        src = photoSheet?.src ?: "",
        sourceOrigin = photoSheet?.sourceOrigin,
        onDismissRequest = dismissSheet(),
    )
    val bookmarkSheet = state.activeSheet as? ReadBookSheet.Bookmark
    bookmarkSheet?.let { sheet ->
        BookmarkEditSheet(
            show = sheetsVisible,
            bookmark = sheet.bookmark,
            onDismiss = { onIntent(ReadBookIntent.DismissSheetIfTop(sheet)) },
            onSave = { onIntent(ReadBookIntent.SaveBookmark(it)) },
            onDelete = { onIntent(ReadBookIntent.DeleteBookmark(it)) },
        )
    }

    val showCharsetSheet = visibleState.findSheet<ReadBookSheet.Charset>() != null
    val showSimulatedReadingSheet = visibleState.findSheet<ReadBookSheet.SimulatedReading>() != null

    // AlertDialog-based sheets and special cases — conditionally composed
    val sendSheetIntent = onIntent
    state.sheetStack.filter {
        sheetsVisible || it is ReadBookSheet.ChangeBookSource || it is ReadBookSheet.ChangeChapterSource
    }.forEach { sheet ->
        androidx.compose.runtime.key(sheet::class) {
            val onIntent: (ReadBookIntent) -> Unit = { intent ->
                sendSheetIntent(
                    if (intent == ReadBookIntent.DismissSheet ||
                        intent == ReadBookIntent.SetActiveSheet(null)
                    ) ReadBookIntent.DismissSheetIfTop(sheet) else intent
                )
            }
            val dismissSheet = { onIntent(ReadBookIntent.DismissSheet) }
            when (sheet) {
                is ReadBookSheet.ClickActionConfig -> {
                    ClickActionConfigSheet(
                        onDismissRequest = dismissSheet,
                    )
                }

                is ReadBookSheet.PageKeyConfig -> {
                    PageKeyConfigSheet(
                        onDismissRequest = dismissSheet,
                    )
                }


                is ReadBookSheet.PageAnim -> {
                    PageAnimConfigSheet(
                        onDismissRequest = dismissSheet,
                        onAnimChanged = { onIntent(ReadBookIntent.PageAnimChanged) },
                    )
                }

                is ReadBookSheet.Download -> {
                    DownloadSheet(
                        onDismissRequest = dismissSheet,
                        onDownload = { start, end ->
                            onIntent(ReadBookIntent.DismissSheet)
                            onIntent(ReadBookIntent.DownloadChapters(start, end))
                        },
                    )
                }

                is ReadBookSheet.AudioDownload -> Unit

                is ReadBookSheet.Charset -> {
                    CharsetConfigSheet(
                        show = showCharsetSheet,
                        onDismissRequest = dismissSheet,
                    )
                }

                is ReadBookSheet.SimulatedReading -> {
                    SimulatedReadingSheet(
                        show = showSimulatedReadingSheet,
                        onDismissRequest = dismissSheet,
                        onApply = { onIntent(ReadBookIntent.ApplySimulatedReading) },
                    )
                }

                is ReadBookSheet.Bookmark -> Unit

                is ReadBookSheet.BookNavigation -> Unit

                is ReadBookSheet.InfoConfig -> {
                    // Integrated into TypographyPage
                    LaunchedEffect(sheet) {
                        onIntent(ReadBookIntent.DismissSheet)
                    }
                }

                is ReadBookSheet.ChangeChapterSource -> {
                    val book = state.book
                    if (book != null) {
                        var showSheet by remember { mutableStateOf(true) }
                        LaunchedEffect(showSheet) {
                            if (!showSheet) {
                                kotlinx.coroutines.delay(300)
                                onIntent(ReadBookIntent.SetActiveSheet(null))
                            }
                        }
                        val viewModel = androidx.compose.runtime.key(
                            "chapter-source-${book.bookUrl}-${sheet.chapterIndex}"
                        ) {
                            org.koin.androidx.compose.koinViewModel<io.legado.app.ui.book.changesource.ChangeChapterSourceViewModel>()
                        }
                        androidx.compose.runtime.DisposableEffect(viewModel) {
                            onDispose { viewModel.dispose() }
                        }
                        LaunchedEffect(book.bookUrl, sheet.chapterIndex) {
                            viewModel.initData(
                                book,
                                sheet.chapterIndex,
                                sheet.chapterTitle
                            )
                        }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        ChangeChapterSourceSheet(
                            state = viewModel.uiState.collectAsStateWithLifecycle().value,
                            onIntent = viewModel::onIntent,
                            show = showSheet && sheetsVisible,
                            onDismissRequest = { showSheet = false },
                            onAnimationFinish = {
                                if (!showSheet) onIntent(ReadBookIntent.SetActiveSheet(null))
                            },
                            bookScoreFlow = viewModel::bookScoreFlow,
                            onBookScoreClick = viewModel::onBookScoreClick,
                            onEditSource = { sourceUrl ->
                                onIntent(ReadBookIntent.OpenSourceEditByUrl(sourceUrl))
                            },
                        )
                        // Handle ReplaceContent effect
                        LaunchedEffect(viewModel) {
                            viewModel.effects.collectLatest { effect ->
                                when (effect) {
                                    is io.legado.app.ui.book.changesource.ChangeChapterSourceEffect.ReplaceContent -> {
                                        showSheet = false
                                        onIntent(
                                            ReadBookIntent.SaveChapterContent(
                                                effect.content,
                                                sheet.chapterIndex
                                            )
                                        )
                                    }

                                    is io.legado.app.ui.book.changesource.ChangeChapterSourceEffect.ShowToast -> {
                                        context.toastOnUi(effect.message)
                                    }

                                    is io.legado.app.ui.book.changesource.ChangeChapterSourceEffect.Dismiss -> {
                                        // Handled by showSheet animation — no-op
                                    }
                                }
                            }
                        }
                    } else {
                        LaunchedEffect(sheet) {
                            onIntent(ReadBookIntent.DismissSheet)
                        }
                    }
                }

                is ReadBookSheet.ChangeBookSource -> {
                    val changeSourceSheet = sheet
                    val changeSourceBook = state.book
                    if (changeSourceBook == null) {
                        LaunchedEffect(changeSourceSheet) {
                            onIntent(ReadBookIntent.DismissSheet)
                        }
                    }
                    if (changeSourceBook != null) {
                        ChangeSourceSheet(
                            show = sheetsVisible,
                            oldBook = changeSourceBook,
                            fromReadBookActivity = true,
                            allowAddAsNew = false,
                            dismissOnReplaceStart = true,
                            onEditSource = { onIntent(ReadBookIntent.OpenSourceEditByUrl(it)) },
                            editedSourceUrl = state.pendingEditedSource,
                            onSourceEditHandled = { onIntent(ReadBookIntent.SourceEditHandled(it)) },
                            onDismissRequest = { onIntent(ReadBookIntent.DismissSheet) },
                            onReplace = { _, newBook, toc, _ ->
                                onIntent(ReadBookIntent.DismissSheet)
                                onIntent(ReadBookIntent.ChangeSource(newBook, toc))
                            },
                            onReplaceBook = { newBook ->
                                onIntent(ReadBookIntent.ChangeSourceBook(newBook))
                            },
                            onAddAsNew = { newBook, toc ->
                                onIntent(ReadBookIntent.DismissSheet)
                                onIntent(ReadBookIntent.AddSourceAsNewBook(newBook, toc))
                            },
                            // fork：换源时保留「换本地书」入口
                            onReplaceLocal = { localBook, toc, _ ->
                                onIntent(ReadBookIntent.ChangeSource(localBook, toc))
                            },
                        )
                    }
                }

                // Sheets using AppModalBottomSheet are composed unconditionally above
                else -> {}
            }
        }

    }
}
