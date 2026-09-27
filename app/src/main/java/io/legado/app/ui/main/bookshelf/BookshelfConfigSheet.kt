package io.legado.app.ui.main.bookshelf

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.domain.model.settings.ContainerNineSlice
import io.legado.app.domain.model.settings.parseContainerNineSlice
import io.legado.app.domain.model.settings.toCsv
import io.legado.app.feature.reader.platform.ReaderTextBackgroundLoader
import io.legado.app.ui.config.themeConfig.BackgroundImageExtraOption
import io.legado.app.ui.config.themeConfig.BackgroundImageManageSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.NinePatchEditorDialog
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.divider.PillDivider
import io.legado.app.ui.widget.components.divider.PillHeaderDivider
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.CompactClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.CompactDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.CompactSliderSettingItem
import io.legado.app.ui.widget.components.settingItem.CompactSwitchSettingItem
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfConfigSheet(
    show: Boolean,
    settings: BookshelfSettings,
    onUpdate: ((BookshelfSettings) -> BookshelfSettings) -> Unit,
    enableCustomTagColors: Boolean,
    themeColor: Int,
    onCustomTagColorsEnabledChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showColorPicker by remember { mutableStateOf(false) }
    var showColorPickerDark by remember { mutableStateOf(false) }
    var showCardImageSheet by remember { mutableStateOf(false) }
    // 正在切图的槽位：(本地图片路径, 是否夜间)；null = 编辑器不显示
    var cardImageEditor by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingCardImageDark by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    // 两个选择器共用同一落盘逻辑：图片应用（GetContent）与文件选择器（OpenDocument，选 .9.png 用）
    fun onCardImagePicked(uri: Uri?) {
        if (uri == null) return
        val dark = pendingCardImageDark
        importScope.launch(Dispatchers.IO) {
            runCatching {
                val folder = if (dark) "bookshelf_card_dark" else "bookshelf_card_light"
                val newPath = importBookshelfCardImage(context, uri.toString(), folder)
                // 落盘即解码进内存缓存：切图编辑器与书卡片首帧命中同步快路径，
                // 滚动时回收行不再出现"整图 Crop 先大一点、再跳回九宫格"
                ReaderTextBackgroundLoader.load(newPath)
                ReaderTextBackgroundLoader.nineSliceFractions(newPath)
                val oldPath = if (dark) {
                    settings.bookshelfCardImageDark
                } else {
                    settings.bookshelfCardImageLight
                }
                // 换图即作废旧切分线：与新图同一次 copy 原子提交（与容器背景图同口径）
                onUpdate {
                    if (dark) {
                        it.copy(
                            bookshelfCardImageDark = newPath,
                            bookshelfCardNineSliceDark = null,
                        )
                    } else {
                        it.copy(
                            bookshelfCardImageLight = newPath,
                            bookshelfCardNineSliceLight = null,
                        )
                    }
                }
                deleteOwnedCardImage(context, oldPath, newPath)
                cardImageEditor = newPath to dark
            }
        }
    }
    val cardImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> onCardImagePicked(uri) }
    val cardImageFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onCardImagePicked(uri) }

    AppModalBottomSheet(
        title = stringResource(R.string.bookshelf_layout),
        show = show,
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillHeaderDivider(title = stringResource(R.string.group))

            CompactDropdownSettingItem(
                title = stringResource(R.string.group_style),
                selectedValue = settings.bookGroupStyle.toString(),
                displayEntries = stringArrayResource(R.array.group_style),
                entryValues = Array(stringArrayResource(R.array.group_style).size) { it.toString() },
                onValueChange = { value ->
                    onUpdate { it.copy(bookGroupStyle = value.toInt()) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.hide_empty_groups),
                checked = settings.hideEmptyGroups,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(hideEmptyGroups = value) }
                }
            )

            PillHeaderDivider(title = stringResource(R.string.sort))

            CompactDropdownSettingItem(
                title = stringResource(R.string.sort),
                selectedValue = settings.bookshelfSort.toString(),
                displayEntries = stringArrayResource(R.array.bookshelf_px_array),
                entryValues = Array(stringArrayResource(R.array.bookshelf_px_array).size) { it.toString() },
                onValueChange = { value ->
                    onUpdate { it.copy(bookshelfSort = value.toInt()) }
                }
            )

            // Sort Order
            CompactDropdownSettingItem(
                title = stringResource(R.string.sort_order),
                selectedValue = settings.bookshelfSortOrder.toString(),
                displayEntries = arrayOf(
                    stringResource(R.string.ascending_order),
                    stringResource(R.string.descending_order)
                ),
                entryValues = arrayOf("0", "1"),
                onValueChange = { value ->
                    onUpdate { it.copy(bookshelfSortOrder = value.toInt()) }
                }
            )

            PillHeaderDivider(title = stringResource(R.string.bookshelf_section_layout))

            // Layout Mode (non-folder)
            val layoutMode =
                if (isLandscape) settings.bookshelfLayoutModeLandscape
                else settings.bookshelfLayoutModePortrait
            val folderLayoutMode =
                if (isLandscape) settings.bookshelfFolderLayoutModeLandscape
                else settings.bookshelfFolderLayoutModePortrait

            // Folder Layout Mode
            AnimatedVisibility(visible = settings.bookGroupStyle == 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    CompactDropdownSettingItem(
                        title = stringResource(R.string.folder_layout_mode),
                        description = stringResource(if (isLandscape) R.string.screen_landscape else R.string.screen_portrait),
                        selectedValue = folderLayoutMode.toString(),
                        displayEntries = arrayOf(stringResource(R.string.layout_mode_list), stringResource(R.string.layout_mode_grid)),
                        entryValues = arrayOf("0", "1"),
                        onValueChange = { value ->
                            onUpdate {
                                if (isLandscape) {
                                    it.copy(bookshelfFolderLayoutModeLandscape = value.toInt())
                                } else {
                                    it.copy(bookshelfFolderLayoutModePortrait = value.toInt())
                                }
                            }
                        }
                    )

                    AnimatedVisibility(visible = folderLayoutMode == 1) {
                        val folderGridCount =
                            if (isLandscape) settings.bookshelfFolderLayoutGridLandscape
                            else settings.bookshelfFolderLayoutGridPortrait
                        CompactSliderSettingItem(
                            title = stringResource(R.string.number_rows_columns),
                            value = folderGridCount.toFloat(),
                            valueRange = 1f..15f,
                            steps = 14,
                            onValueChange = { value ->
                                onUpdate {
                                    if (isLandscape) {
                                        it.copy(bookshelfFolderLayoutGridLandscape = value.toInt())
                                    } else {
                                        it.copy(bookshelfFolderLayoutGridPortrait = value.toInt())
                                    }
                                }
                            }
                        )
                    }

                    AnimatedVisibility(visible = folderLayoutMode != 1) {
                        val folderListCount =
                            if (isLandscape) settings.bookshelfFolderLayoutListLandscape
                            else settings.bookshelfFolderLayoutListPortrait
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactSliderSettingItem(
                                title = stringResource(R.string.number_rows_columns),
                                value = folderListCount.toFloat(),
                                valueRange = 1f..5f,
                                steps = 4,
                                onValueChange = { value ->
                                    onUpdate {
                                        if (isLandscape) {
                                            it.copy(bookshelfFolderLayoutListLandscape = value.toInt())
                                        } else {
                                            it.copy(bookshelfFolderLayoutListPortrait = value.toInt())
                                        }
                                    }
                                }
                            )

                    CompactSliderSettingItem(
                        title = stringResource(R.string.list_cover_width),
                        value = settings.bookshelfListCoverWidth.toFloat(),
                        valueRange = 0f..120f,
                        steps = 120,
                        onValueChange = { value ->
                            onUpdate { it.copy(bookshelfListCoverWidth = value.toInt()) }
                        }
                    )
                        }
                    }

                    AnimatedVisibility(
                        visible = settings.bookGroupStyle == 2 && folderLayoutMode == 0
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactDropdownSettingItem(
                                title = stringResource(R.string.folder_list_style),
                                selectedValue = settings.bookshelfGroupListStyle.toString(),
                                displayEntries = arrayOf(stringResource(R.string.group), stringResource(R.string.compact_list), stringResource(R.string.horizontal_cover_count)),
                                entryValues = arrayOf("0", "1", "2"),
                                onValueChange = { value ->
                                    onUpdate { it.copy(bookshelfGroupListStyle = value.toInt()) }
                                }
                            )
                            AnimatedVisibility(visible = settings.bookshelfGroupListStyle == 2) {
                                CompactSliderSettingItem(
                                    title = stringResource(R.string.horizontal_cover_count),
                                    value = settings.bookshelfGroupCoverCount.toFloat(),
                                    valueRange = 1f..10f,
                                    steps = 9,
                                    onValueChange = { value ->
                                        onUpdate { it.copy(bookshelfGroupCoverCount = value.toInt()) }
                                    }
                                )
                            }
                        }
                    }

                    PillDivider()
                }
            }

            CompactDropdownSettingItem(
                title = stringResource(R.string.layout_mode),
                description = stringResource(if (isLandscape) R.string.screen_landscape else R.string.screen_portrait),
                selectedValue = layoutMode.toString(),
                displayEntries = arrayOf(
                    stringResource(R.string.layout_mode_list),
                    stringResource(R.string.layout_mode_grid)
                ),
                entryValues = arrayOf("0", "1"),
                onValueChange = { value ->
                    onUpdate {
                        if (isLandscape) {
                            it.copy(bookshelfLayoutModeLandscape = value.toInt())
                        } else {
                            it.copy(bookshelfLayoutModePortrait = value.toInt())
                        }
                    }
                }
            )

            AnimatedVisibility(
                visible = layoutMode == 1
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactDropdownSettingItem(
                        title = stringResource(R.string.grid_style),
                        selectedValue = settings.bookshelfGridLayout.toString(),
                        displayEntries = stringArrayResource(R.array.bookshelf_grid_layout),
                        entryValues = Array(stringArrayResource(R.array.bookshelf_grid_layout).size) { it.toString() },
                        onValueChange = { value ->
                            onUpdate { it.copy(bookshelfGridLayout = value.toInt()) }
                        }
                    )

                    val gridCount =
                        if (isLandscape) settings.bookshelfLayoutGridLandscape else settings.bookshelfLayoutGridPortrait
                    CompactSliderSettingItem(
                        title = stringResource(R.string.number_rows_columns),
                        value = gridCount.toFloat(),
                        valueRange = 1f..15f,
                        steps = 14,
                        onValueChange = { value ->
                            onUpdate {
                                if (isLandscape) {
                                    it.copy(bookshelfLayoutGridLandscape = value.toInt())
                                } else {
                                    it.copy(bookshelfLayoutGridPortrait = value.toInt())
                                }
                            }
                        }
                    )

                    CompactSwitchSettingItem(
                        title = stringResource(R.string.compact_title_font),
                        checked = settings.bookshelfTitleSmallFont,
                        color = LegadoTheme.colorScheme.surface,
                        onCheckedChange = { value ->
                            onUpdate { it.copy(bookshelfTitleSmallFont = value) }
                        }
                    )

                    CompactSwitchSettingItem(
                        title = stringResource(R.string.center_aligned_title),
                        checked = settings.bookshelfTitleCenter,
                        color = LegadoTheme.colorScheme.surface,
                        onCheckedChange = { value ->
                            onUpdate { it.copy(bookshelfTitleCenter = value) }
                        }
                    )

                    CompactSliderSettingItem(
                        title = stringResource(R.string.grid_cover_width),
                        value = settings.bookshelfGridCoverWidth.toFloat(),
                        valueRange = 40f..150f,
                        steps = 110,
                        onValueChange = { value ->
                            onUpdate { it.copy(bookshelfGridCoverWidth = value.toInt()) }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = layoutMode != 1
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactSwitchSettingItem(
                        title = stringResource(R.string.show_divider_line),
                        checked = settings.bookshelfShowDivider,
                        color = LegadoTheme.colorScheme.surface,
                        onCheckedChange = { value ->
                            onUpdate { it.copy(bookshelfShowDivider = value) }
                        }
                    )

                    CompactClickableSettingItem(
                        title = stringResource(R.string.day_card_bg_color),
                        color = LegadoTheme.colorScheme.surface,
                        onClick = { showColorPicker = true },
                        trailingContent = {
                            if (settings.bookshelfCardColor != 0) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(settings.bookshelfCardColor))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                )
                            }
                        }
                    )

                    CompactClickableSettingItem(
                        title = stringResource(R.string.night_card_bg_color),
                        color = LegadoTheme.colorScheme.surface,
                        onClick = { showColorPickerDark = true },
                        trailingContent = {
                            if (settings.bookshelfCardColorDark != 0) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(settings.bookshelfCardColorDark))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                )
                            }
                        }
                    )

                    CompactClickableSettingItem(
                        title = stringResource(R.string.card_bg_image),
                        description = stringResource(R.string.card_bg_image_desc),
                        color = LegadoTheme.colorScheme.surface,
                        onClick = { showCardImageSheet = true },
                        trailingContent = {
                            if (!settings.bookshelfCardImageLight.isNullOrBlank() ||
                                !settings.bookshelfCardImageDark.isNullOrBlank()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(LegadoTheme.colorScheme.primary)
                                )
                            }
                        }
                    )

                    CompactSwitchSettingItem(
                        title = stringResource(R.string.compact_details),
                        checked = settings.bookshelfLayoutCompact,
                        color = LegadoTheme.colorScheme.surface,
                        onCheckedChange = { value ->
                            onUpdate { it.copy(bookshelfLayoutCompact = value) }
                        }
                    )

                    val listColCount =
                        if (isLandscape) settings.bookshelfLayoutListLandscape else settings.bookshelfLayoutListPortrait
                    CompactSliderSettingItem(
                        title = stringResource(R.string.list_cover_width),
                        value = settings.bookshelfListCoverWidth.toFloat(),
                        valueRange = 40f..120f,
                        steps = 80,
                        onValueChange = { value ->
                            onUpdate { it.copy(bookshelfListCoverWidth = value.toInt()) }
                        }
                    )

                    CompactSliderSettingItem(
                        title = stringResource(R.string.number_rows_columns),
                        value = listColCount.toFloat(),
                        valueRange = 1f..5f,
                        steps = 4,
                        onValueChange = { value ->
                            onUpdate {
                                if (isLandscape) {
                                    it.copy(bookshelfLayoutListLandscape = value.toInt())
                                } else {
                                    it.copy(bookshelfLayoutListPortrait = value.toInt())
                                }
                            }
                        }
                    )

                    CompactSwitchSettingItem(
                        title = stringResource(R.string.show_more_info),
                        checked = settings.showBookIntro,
                        color = LegadoTheme.colorScheme.surface,
                        onCheckedChange = { value ->
                            onUpdate { it.copy(showBookIntro = value) }
                        }
                    )

                    AnimatedVisibility(visible = settings.showBookIntro) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactSwitchSettingItem(
                                title = stringResource(R.string.center_cover_vertically),
                                description = stringResource(R.string.center_cover_vertically_description),
                                checked = settings.bookshelfListCoverCenter,
                                color = LegadoTheme.colorScheme.surface,
                                onCheckedChange = { value ->
                                    onUpdate { it.copy(bookshelfListCoverCenter = value) }
                                }
                            )
                            CompactSwitchSettingItem(
                                title = stringResource(R.string.show_latest_chapter),
                                checked = settings.bookshelfShowLatestChapter,
                                color = LegadoTheme.colorScheme.surface,
                                onCheckedChange = { value ->
                                    onUpdate { it.copy(bookshelfShowLatestChapter = value) }
                                }
                            )

                            CompactSwitchSettingItem(
                                title = stringResource(R.string.show_synopsis),
                                checked = settings.bookshelfShowIntro,
                                color = LegadoTheme.colorScheme.surface,
                                onCheckedChange = { value ->
                                    onUpdate { it.copy(bookshelfShowIntro = value) }
                                }
                            )
                            AnimatedVisibility(visible = settings.bookshelfShowIntro && layoutMode == 0) {
                                CompactSwitchSettingItem(
                                    title = stringResource(R.string.show_synopsis_below_content),
                                    checked = settings.bookshelfListIntroBelowContent,
                                    color = LegadoTheme.colorScheme.surface,
                                    onCheckedChange = { value ->
                                        onUpdate { it.copy(bookshelfListIntroBelowContent = value) }
                                    }
                                )
                            }
                            AnimatedVisibility(
                                visible = settings.bookshelfShowIntro && settings.bookshelfListIntroBelowContent && layoutMode == 0
                            ) {
                                CompactSwitchSettingItem(
                                    title = stringResource(R.string.ticket_style),
                                    checked = settings.bookshelfTicketStyle,
                                    color = LegadoTheme.colorScheme.surface,
                                    onCheckedChange = { value ->
                                        onUpdate { it.copy(bookshelfTicketStyle = value) }
                                    }
                                )
                            }
                            AnimatedVisibility(
                                visible = settings.bookshelfShowIntro && settings.bookshelfListIntroBelowContent && settings.bookshelfTicketStyle && layoutMode == 0
                            ) {
                                CompactDropdownSettingItem(
                                    title = stringResource(R.string.ticket_dash_style),
                                    selectedValue = if (settings.bookshelfTicketDotted) "1" else "0",
                                    displayEntries = arrayOf(
                                        stringResource(R.string.ticket_dash_style_dash),
                                        stringResource(R.string.ticket_dash_style_dot),
                                    ),
                                    entryValues = arrayOf("0", "1"),
                                    onValueChange = { value ->
                                        onUpdate { it.copy(bookshelfTicketDotted = value == "1") }
                                    }
                                )
                            }
                            AnimatedVisibility(
                                visible = settings.bookshelfShowIntro
                            ) {
                                CompactSliderSettingItem(
                                    title = stringResource(R.string.synopsis_lines),
                                    description = if (settings.bookshelfIntroMaxLines == 0) stringResource(R.string.show_all_synopsis) else stringResource(R.string.show_lines_synopsis, settings.bookshelfIntroMaxLines),
                                    value = settings.bookshelfIntroMaxLines.toFloat(),
                                    valueRange = 0f..10f,
                                    steps = 10,
                                    onValueChange = { value ->
                                        onUpdate { it.copy(bookshelfIntroMaxLines = value.toInt()) }
                                    }
                                )
                            }
                            CompactSwitchSettingItem(
                                title = stringResource(R.string.show_tags),
                                checked = settings.bookshelfShowTag,
                                color = LegadoTheme.colorScheme.surface,
                                onCheckedChange = { value ->
                                    onUpdate { it.copy(bookshelfShowTag = value) }
                                }
                            )
                            AnimatedVisibility(
                                visible = settings.bookshelfShowTag
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactSwitchSettingItem(
                                        title = stringResource(R.string.custom_tag_colors),
                                        checked = enableCustomTagColors,
                                        color = LegadoTheme.colorScheme.surface,
                                        onCheckedChange = onCustomTagColorsEnabledChange
                                    )
                                    CompactSwitchSettingItem(
                                        title = stringResource(R.string.tag_border),
                                        checked = settings.bookshelfTagBorder,
                                        color = LegadoTheme.colorScheme.surface,
                                        onCheckedChange = { value ->
                                            onUpdate { it.copy(bookshelfTagBorder = value) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }



            CompactSliderSettingItem(
                title = stringResource(R.string.max_title_lines),
                value = settings.bookshelfTitleMaxLines.toFloat(),
                valueRange = 1f..5f,
                steps = 4,
                onValueChange = { value ->
                    onUpdate { it.copy(bookshelfTitleMaxLines = value.toInt()) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.cover_shadow),
                checked = settings.bookshelfCoverShadow,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(bookshelfCoverShadow = value) }
                }
            )

            PillHeaderDivider(title = stringResource(R.string.bookshelf_section_badge))

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_unread),
                checked = settings.showUnread,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showUnread = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_unread_new),
                checked = settings.showUnreadNew,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showUnreadNew = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_wait_up_count),
                checked = settings.showWaitUpCount,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showWaitUpCount = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_book_count),
                checked = settings.showBookCount,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showBookCount = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_last_update_time),
                checked = settings.showLastUpdateTime,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showLastUpdateTime = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_tip),
                checked = settings.showTip,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showTip = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_reading_progress),
                checked = settings.showReadingProgress,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showReadingProgress = value) }
                }
            )

            PillHeaderDivider(title = stringResource(R.string.other))

            CompactSwitchSettingItem(
                title = stringResource(R.string.search_filter_first),
                checked = settings.bookshelfSearchActionDirectToSearch,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(bookshelfSearchActionDirectToSearch = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_bookshelf_fast_scroller),
                checked = settings.showBookshelfFastScroller,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showBookshelfFastScroller = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_bookshelf_tab_menu),
                checked = settings.shouldShowExpandButton,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(shouldShowExpandButton = value) }
                }
            )

            CompactSwitchSettingItem(
                title = stringResource(R.string.show_bookshelf_tag_filter),
                checked = settings.showBookshelfTagFilter,
                color = LegadoTheme.colorScheme.surface,
                onCheckedChange = { value ->
                    onUpdate { it.copy(showBookshelfTagFilter = value) }
                }
            )

            CompactSliderSettingItem(
                title = stringResource(R.string.bookshelf_update_limit),
                description = if (settings.bookshelfRefreshingLimit <= 0) stringResource(R.string.refresh_limit_unlimited) else stringResource(R.string.refresh_limit_books, settings.bookshelfRefreshingLimit),
                value = settings.bookshelfRefreshingLimit.toFloat(),
                valueRange = 0f..100f,
                steps = 100,
                onValueChange = { value ->
                    onUpdate { it.copy(bookshelfRefreshingLimit = value.toInt()) }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

        }

        ColorPickerSheet(
            show = showColorPicker,
            initialColor = if (settings.bookshelfCardColor != 0) settings.bookshelfCardColor else LegadoTheme.colorScheme.surfaceVariant.toArgb(),
            onDismissRequest = { showColorPicker = false },
            onColorSelected = { value ->
                onUpdate { it.copy(bookshelfCardColor = value) }
            }
        )

        ColorPickerSheet(
            show = showColorPickerDark,
            initialColor = if (settings.bookshelfCardColorDark != 0) settings.bookshelfCardColorDark else LegadoTheme.colorScheme.surfaceVariant.toArgb(),
            onDismissRequest = { showColorPickerDark = false },
            onColorSelected = { value ->
                onUpdate { it.copy(bookshelfCardColorDark = value) }
            }
        )

        // 卡片背景图：日/夜双图 + 图案大小，复用容器背景图同一套管理面板
        BackgroundImageManageSheet(
            show = showCardImageSheet,
            onDismissRequest = { showCardImageSheet = false },
            title = stringResource(R.string.card_bg_image),
            lightPath = settings.bookshelfCardImageLight,
            darkPath = settings.bookshelfCardImageDark,
            extraOptions = listOf(
                BackgroundImageExtraOption.NineScale(
                    title = "图案大小",
                    value = settings.bookshelfCardNineScale,
                    onValueChange = { value ->
                        onUpdate { it.copy(bookshelfCardNineScale = value) }
                    },
                )
            ),
            onSelectLight = { useFilePicker ->
                pendingCardImageDark = false
                if (useFilePicker) {
                    cardImageFileLauncher.launch(arrayOf("image/*"))
                } else {
                    cardImageLauncher.launch("image/*")
                }
            },
            onSelectDark = { useFilePicker ->
                pendingCardImageDark = true
                if (useFilePicker) {
                    cardImageFileLauncher.launch(arrayOf("image/*"))
                } else {
                    cardImageLauncher.launch("image/*")
                }
            },
            onRemoveLight = {
                onUpdate {
                    it.copy(bookshelfCardImageLight = null, bookshelfCardNineSliceLight = null)
                }
                deleteOwnedCardImage(context, settings.bookshelfCardImageLight, null)
            },
            onRemoveDark = {
                onUpdate {
                    it.copy(bookshelfCardImageDark = null, bookshelfCardNineSliceDark = null)
                }
                deleteOwnedCardImage(context, settings.bookshelfCardImageDark, null)
            },
            onEditLight = settings.bookshelfCardImageLight?.let { path ->
                { cardImageEditor = path to false }
            },
            onEditDark = settings.bookshelfCardImageDark?.let { path ->
                { cardImageEditor = path to true }
            },
        )

        // 九宫格切图编辑器：初始线优先回读已保存切分线，其次 .9.png 引导线自动探测，最后默认线
        val editorSlot = cardImageEditor
        val editorInitial = remember(editorSlot) {
            editorSlot?.let { (path, dark) ->
                val saved = parseContainerNineSlice(
                    if (dark) {
                        settings.bookshelfCardNineSliceDark
                    } else {
                        settings.bookshelfCardNineSliceLight
                    }
                )
                val auto = ReaderTextBackgroundLoader.nineSliceFractions(path)
                floatArrayOf(
                    saved?.leftX ?: auto?.left ?: 0.25f,
                    saved?.rightX ?: auto?.let { 1f - it.right } ?: 0.75f,
                    saved?.topY ?: auto?.top ?: 0.25f,
                    saved?.bottomY ?: auto?.let { 1f - it.bottom } ?: 0.75f,
                )
            }
        }
        NinePatchEditorDialog(
            show = editorSlot != null,
            imagePath = editorSlot?.first.orEmpty(),
            initialLeft = editorInitial?.get(0) ?: 0.25f,
            initialRight = editorInitial?.get(1) ?: 0.75f,
            initialTop = editorInitial?.get(2) ?: 0.25f,
            initialBottom = editorInitial?.get(3) ?: 0.75f,
            onDismissRequest = { cardImageEditor = null },
            onSave = { left, right, top, bottom ->
                val dark = editorSlot?.second
                if (dark != null) {
                    // 绝对线位置 → 四角占比 CSV，与容器背景图同一换算
                    val csv = ContainerNineSlice(
                        left = left,
                        right = 1f - right,
                        top = top,
                        bottom = 1f - bottom,
                    ).toCsv()
                    onUpdate {
                        if (dark) {
                            it.copy(bookshelfCardNineSliceDark = csv)
                        } else {
                            it.copy(bookshelfCardNineSliceLight = csv)
                        }
                    }
                }
                cardImageEditor = null
            },
        )
    }
}

/** 把选中的图片复制进应用目录并以 MD5 命名，返回绝对路径；同一张图重复选择命中同一路径 */
private fun importBookshelfCardImage(context: Context, uriString: String, folderName: String): String {
    val uri = Uri.parse(uriString)
    val fileDoc = FileDoc.fromUri(uri, false)
    val suffix = if (fileDoc.name.endsWith(".9.png", ignoreCase = true)) {
        "9.png"
    } else {
        fileDoc.name.substringAfterLast(".", "jpg")
    }
    val md5 = uri.inputStream(context).getOrThrow().use(MD5Utils::md5Encode)
    val file = File(File(context.externalFiles, folderName), "$md5.$suffix")
    if (!file.exists()) {
        FileUtils.createFileIfNotExist(file.absolutePath)
        uri.inputStream(context).getOrThrow().use { input ->
            FileOutputStream(file).use(input::copyTo)
        }
    }
    return file.absolutePath
}

/** 只删应用自己目录内的落盘副本，绝不触碰用户原始文件 */
private fun deleteOwnedCardImage(context: Context, oldPath: String?, newPath: String?) {
    if (oldPath.isNullOrBlank() || oldPath == newPath) return
    File(oldPath)
        .takeIf { it.absolutePath.startsWith(context.externalFiles.absolutePath) }
        ?.delete()
}
