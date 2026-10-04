package io.legado.app.ui.book.read.sheet

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
// Dp.isFinite 是这个包里的扩展属性，不是 kotlin.math 那个 Float 版本，必须显式导入
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.data.repository.configNames
import io.legado.app.data.repository.toJsonArray
import io.legado.app.feature.reader.core.style.READER_DOUBLE_LINE_GAP_DP
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.underlineControlSupport
import io.legado.app.feature.reader.platform.ReaderAndroidPaintFactory
import io.legado.app.feature.reader.platform.ReaderTextBackgroundLoader
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadStyleResolver
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.FontFolderState
import io.legado.app.ui.widget.components.FontSelectSheet
import io.legado.app.ui.widget.components.NinePatchEditorDialog
import io.legado.app.ui.widget.components.SectionTitle
import io.legado.app.ui.widget.components.ValueStepper
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyClearColorModeSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.SelectImageContract
import io.legado.app.utils.launch
import io.legado.app.utils.textHeight
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.text.drawFluorescentBand
import io.legado.app.ui.widget.components.text.drawUnderlineSegment
import io.legado.app.ui.widget.components.text.forEachLineSegment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

@Composable
fun HighlightRuleEditSheet(
    show: Boolean,
    rule: HighlightRule?,
    allConfigNames: List<String>,
    onDismissRequest: () -> Unit,
    onSave: (HighlightRule) -> Unit,
) {
    val isNew = rule == null
    val initial = remember(show, rule) { rule ?: HighlightRule() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Rule info state
    var pattern by remember(show, rule) { mutableStateOf(initial.pattern) }
    var name by remember(show, rule) { mutableStateOf(initial.name) }
    var targetScope by remember(show, rule) { mutableIntStateOf(initial.targetScope) }
    var enabled by remember(show, rule) { mutableStateOf(initial.enabled) }
    var sampleText by remember(show, rule) {
        mutableStateOf(initial.sampleText.ifBlank { "她轻声说：今晚就出发。" })
    }

    // Style state
    // 日间色：null 表示自动派生（从夜间色反向派生）
    var textColor by remember(show, rule) { mutableStateOf(initial.textColor) }
    // 夜间色：null 表示自动派生（从日间色正向派生）
    var textColorNight by remember(show, rule) { mutableStateOf(initial.textColorNight) }
    var bgColor by remember(show, rule) { mutableStateOf(initial.bgColor) }
    var bgColorNight by remember(show, rule) { mutableStateOf(initial.bgColorNight) }
    // 0 = 无下划线（原「下划线开关」的关），>0 = 具体线型；开关与样式合并为单一来源
    var underlineMode by remember(
        show,
        rule
    ) { mutableIntStateOf(initial.underlineMode) }
    var underlineColor by remember(show, rule) { mutableStateOf(initial.underlineColor) }
    var underlineColorNight by remember(show, rule) { mutableStateOf(initial.underlineColorNight) }
    // 初值钳到滑块范围：sanitizeRule 只在保存/整表加载时跑，备份导入或直接改库的
    // 越界值会一路带到滑块上，越界状态下组件行为不可预期
    var underlineWidth by remember(show, rule) {
        mutableFloatStateOf(initial.underlineWidth.coerceIn(0.1f, 10f))
    }
    var underlineOffset by remember(show, rule) {
        mutableFloatStateOf(initial.underlineOffset.coerceIn(-10f, 20f))
    }
    var underlineSvgPath by remember(
        show,
        rule
    ) { mutableStateOf(initial.underlineSvgPath.orEmpty()) }
    var underlineBelowText by remember(show, rule) { mutableStateOf(initial.underlineBelowText) }
    var underlineRoundCap by remember(show, rule) { mutableStateOf(initial.underlineRoundCap) }
    var underlineFeather by remember(show, rule) { mutableFloatStateOf(initial.underlineFeather) }
    var underlineDashLen by remember(show, rule) { mutableFloatStateOf(initial.underlineDashLen) }
    var underlineDashGap by remember(show, rule) { mutableFloatStateOf(initial.underlineDashGap) }
    var underlineWavePeak by remember(show, rule) { mutableFloatStateOf(initial.underlineWavePeak) }
    var underlineWaveLength by remember(show, rule) { mutableFloatStateOf(initial.underlineWaveLength) }
    var useProtagonist by remember(show, rule) { mutableStateOf(initial.useProtagonist) }
    var characterRole by remember(show, rule) { mutableStateOf(initial.characterRole.orEmpty()) }
    var bgImage by remember(show, rule) { mutableStateOf(initial.bgImage.orEmpty()) }
    var bgImageFit by remember(show, rule) { mutableIntStateOf(initial.bgImageFit) }
    var bgImageScale by remember(show, rule) { mutableFloatStateOf(initial.bgImageScale) }
    var hasBgImage by remember(show, rule) { mutableStateOf(initial.bgImage?.isNotBlank() == true) }

    // Font weight state
    var fontWeight by remember(show, rule) { mutableIntStateOf(initial.fontWeight) }
    var isItalic by remember(show, rule) { mutableStateOf(initial.isItalic) }
    var fontSizeOffset by remember(show, rule) { mutableIntStateOf(initial.fontSizeOffset) }

    // Nine-slice state
    var npLeft by remember(show, rule) { mutableFloatStateOf(initial.npLeft) }
    var npRight by remember(show, rule) { mutableFloatStateOf(initial.npRight) }
    var npTop by remember(show, rule) { mutableFloatStateOf(initial.npTop) }
    var npBottom by remember(show, rule) { mutableFloatStateOf(initial.npBottom) }
    var bgPaddingStart by remember(show, rule) { mutableFloatStateOf(initial.bgPaddingStart) }
    var bgPaddingEnd by remember(show, rule) { mutableFloatStateOf(initial.bgPaddingEnd) }
    var bgPaddingTop by remember(show, rule) { mutableFloatStateOf(initial.bgPaddingTop) }
    var bgPaddingBottom by remember(show, rule) { mutableFloatStateOf(initial.bgPaddingBottom) }
    var bgMarginStart by remember(show, rule) { mutableFloatStateOf(initial.bgMarginStart) }
    var bgMarginEnd by remember(show, rule) { mutableFloatStateOf(initial.bgMarginEnd) }
    var bgMarginTop by remember(show, rule) { mutableFloatStateOf(initial.bgMarginTop) }
    var bgMarginBottom by remember(show, rule) { mutableFloatStateOf(initial.bgMarginBottom) }
    var showNinePatchEditor by remember(show, rule) { mutableStateOf(false) }
    var showInsetEditor by remember { mutableStateOf(false) }
    var showMarginEditor by remember { mutableStateOf(false) }
    var manualNineSlice by remember(show, rule) { mutableStateOf(initial.manualNineSlice) }

    // 预览必须与正文渲染取同一份切分线来源：非手动且图片带 .9.png 引导线时正文吃自动值，
    // 这里同步换算，避免"预览调好了、正文没生效"。
    val effectiveNineSlice = remember(bgImage, manualNineSlice, npLeft, npRight, npTop, npBottom) {
        val automatic = if (bgImage.isNotBlank() && !manualNineSlice) {
            ReaderTextBackgroundLoader.nineSliceFractions(bgImage)
        } else null
        NineSliceValues(
            left = automatic?.left ?: npLeft,
            right = automatic?.right ?: npRight,
            top = automatic?.top ?: npTop,
            bottom = automatic?.bottom ?: npBottom,
        )
    }

    // Config binding state — empty set = global (applies to all configs)
    var configNames by remember(show, rule) {
        mutableStateOf(initial.configName.orEmpty().configNames().toSet())
    }

    // Font state
    var hasFont by remember(show, rule) { mutableStateOf(initial.fontPath?.isNotBlank() == true) }
    var fontPath by remember(show, rule) { mutableStateOf(initial.fontPath.orEmpty()) }

    // Color picker state
    var showTextColorPicker by remember(show, rule) { mutableStateOf(false) }
    var showTextColorNightPicker by remember(show, rule) { mutableStateOf(false) }
    var showBgColorPicker by remember(show, rule) { mutableStateOf(false) }
    var showBgColorNightPicker by remember(show, rule) { mutableStateOf(false) }
    var showUnderlineColorPicker by remember(show, rule) { mutableStateOf(false) }
    var showUnderlineColorNightPicker by remember(show, rule) { mutableStateOf(false) }
    var showFontSelect by remember(show, rule) { mutableStateOf(false) }

    // Validation
    var patternError by remember(show, rule) { mutableStateOf<String?>(null) }

    // 停靠预览区是否展开：默认展开，调参时随时能看到效果；折叠后只留标题行把高度让给表单
    var previewDockExpanded by remember(show, rule) { mutableStateOf(true) }

    // File picker for background images (uses SelectImageContract for visual photo picker)
    val imagePicker = rememberLauncherForActivityResult(
        SelectImageContract()
    ) { result ->
        val uri = result.uri
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val dir = File(appCtx.filesDir, "bg_images")
                        if (!dir.exists()) dir.mkdirs()
                        val displayName = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    .takeIf { it >= 0 }
                                    ?.let(cursor::getString)
                            } else {
                                null
                            }
                        }
                        val suffix = when {
                            displayName?.endsWith(".9.png", ignoreCase = true) == true -> ".9.png"
                            displayName?.substringAfterLast('.', "").isNullOrBlank() -> ".img"
                            else -> ".${displayName.substringAfterLast('.')}"
                        }
                        val target = File(dir, "bg_${System.currentTimeMillis()}$suffix")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw java.io.FileNotFoundException("Open input stream failed")
                        target.absolutePath
                    }
                }.onSuccess { path ->
                    bgImage = path
                }.onFailure { throwable ->
                    context.toastOnUi(R.string.error)
                    AppLog.put("选择高亮背景图失败", throwable)
                }
            }
        }
    }

    val titleRes = if (isNew) R.string.new_rule else R.string.edit_rule

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(titleRes),
        startAction = {
            // 预览折叠开关与右侧「保存」对称，都走标题栏图标按钮
            MediumTonalButton(
                icon = if (previewDockExpanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowUp
                },
                contentDescription = stringResource(
                    if (previewDockExpanded) R.string.collapse else R.string.expand
                ),
                onClick = { previewDockExpanded = !previewDockExpanded },
            )
        },
        endAction = {
            MediumTonalButton(
                onClick = {
                    if (pattern.isNotBlank()) {
                        val result = runCatching { Regex(pattern) }
                        if (result.isFailure) {
                            patternError = result.exceptionOrNull()?.message
                            return@MediumTonalButton
                        }
                    }
                    patternError = null
                    onSave(
                        HighlightRule(
                            id = initial.id,
                            name = name,
                            pattern = pattern,
                            sampleText = sampleText,
                            targetScope = targetScope,
                            enabled = enabled,
                            position = initial.position,
                            textColor = textColor,
                            textColorNight = textColorNight,
                            bgColor = bgColor,
                            bgColorNight = bgColorNight,
                            underlineMode = underlineMode,
                            underlineColor = if (underlineMode > 0) underlineColor else null,
                            underlineColorNight = if (underlineMode > 0) underlineColorNight else null,
                            underlineWidth = underlineWidth,
                            underlineOffset = underlineOffset,
                            underlineSvgPath = underlineSvgPath.ifBlank { null },
                            underlineRoundCap = underlineRoundCap,
                            underlineFeather = underlineFeather,
                            underlineBelowText = underlineBelowText,
                            underlineDashLen = underlineDashLen,
                            underlineDashGap = underlineDashGap,
                            underlineWavePeak = underlineWavePeak,
                            underlineWaveLength = underlineWaveLength,
                            bgImage = if (hasBgImage) bgImage.ifBlank { null } else null,
                            bgImageFit = if (hasBgImage && bgImage.isNotBlank()) bgImageFit else 0,
                            bgImageScale = bgImageScale,
                            configName = if (configNames.isEmpty()) null else configNames.toList().toJsonArray(),
                            fontPath = if (hasFont) fontPath.ifBlank { null } else null,
                            fontWeight = fontWeight,
                            isItalic = isItalic,
                            fontSizeOffset = fontSizeOffset,
                            npLeft = npLeft,
                            npRight = npRight,
                            npTop = npTop,
                            npBottom = npBottom,
                            bgPaddingStart = bgPaddingStart,
                            bgPaddingEnd = bgPaddingEnd,
                            bgPaddingTop = bgPaddingTop,
                            bgPaddingBottom = bgPaddingBottom,
                            bgMarginStart = bgMarginStart,
                            bgMarginEnd = bgMarginEnd,
                            bgMarginTop = bgMarginTop,
                            bgMarginBottom = bgMarginBottom,
                            useProtagonist = useProtagonist,
                            characterRole = characterRole.ifBlank { null },
                            manualNineSlice = manualNineSlice,
                        )
                    )
                },
                icon = Icons.Default.Done,
                contentDescription = stringResource(R.string.save),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 设置项滚动区：预览停到底部后，表单只能分到「弹层高度 - 预览高度」，
            // 用 weight 吃满剩余高度；fill = false 保证表单内容不足时弹层不被撑到最大高度。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                // === Section 1: Rule Info ===
                SectionTitle(stringResource(R.string.rule_info))

                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.rule_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                AppTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        patternError = null
                    },
                    label = stringResource(R.string.rule_pattern),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = patternError != null,
                    enabled = !useProtagonist,
                    supportingText = patternError?.let {
                        { AppText(it, color = MaterialTheme.colorScheme.error) }
                    },
                )

                Spacer(Modifier.height(8.dp))

                // 跟随主角：用知识图谱中的人物名代替正则
                TinySwitchSettingItem(
                    title = stringResource(R.string.use_protagonist),
                    checked = useProtagonist,
                    onCheckedChange = { useProtagonist = it },
                )
                AnimatedVisibility(visible = useProtagonist) {
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.character_role_filter),
                        selectedValue = characterRole,
                        displayEntries = arrayOf(
                            stringResource(R.string.character_role_all),
                            stringResource(R.string.character_role_male_lead),
                            stringResource(R.string.character_role_female_lead),
                            stringResource(R.string.character_role_male_supporting),
                            stringResource(R.string.character_role_female_supporting),
                        ),
                        entryValues = arrayOf(
                            "",
                            BookCharacterProfile.ROLE_MALE_LEAD,
                            BookCharacterProfile.ROLE_FEMALE_LEAD,
                            BookCharacterProfile.ROLE_MALE_SUPPORTING,
                            BookCharacterProfile.ROLE_FEMALE_SUPPORTING,
                        ),
                        onValueChange = { characterRole = it },
                    )
                }

                Spacer(Modifier.height(8.dp))

                val scopeEntries = arrayOf(
                    stringResource(R.string.target_all),
                    stringResource(R.string.target_title),
                    stringResource(R.string.target_body),
                )
                val scopeValues = arrayOf(
                    HighlightRule.TARGET_ALL.toString(),
                    HighlightRule.TARGET_TITLE.toString(),
                    HighlightRule.TARGET_BODY.toString(),
                )
                TinyDropdownSettingItem(
                    title = stringResource(R.string.target_scope),
                    selectedValue = targetScope.toString(),
                    displayEntries = scopeEntries,
                    entryValues = scopeValues,
                    onValueChange = { targetScope = it.toIntOrNull() ?: HighlightRule.TARGET_ALL },
                )

                TinySwitchSettingItem(
                    title = stringResource(R.string.enable_rule),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )

                // === Section 2: Style Settings ===
                SectionTitle(stringResource(R.string.style_settings))

                // 自定义字体
                TinySwitchSettingItem(
                    title = "自定义字体",
                    checked = hasFont,
                    onCheckedChange = { hasFont = it },
                )
                AnimatedVisibility(visible = hasFont) {
                    TinyClickableSettingItem(
                        title = stringResource(R.string.select_font),
                        description = fontPath.ifBlank { null }?.let { File(it).name },
                        onClick = { showFontSelect = true },
                    )
                }

                // Text color
                TinyClearColorModeSettingItem(
                    title = stringResource(R.string.text_color),
                    dayColor = textColor,
                    nightColor = textColorNight,
                    onClickColor = { isNight ->
                        if (isNight) showTextColorNightPicker = true
                        else showTextColorPicker = true
                    },
                    onClearColor = { _ ->
                        // 刷新：同时清除日间色和夜间色
                        textColor = null
                        textColorNight = null
                    },
                )

                // Font weight — three options: Regular(400), Bold(700), Light(300)
                val weightEntries = stringArrayResource(R.array.text_font_weight)
                TinyDropdownSettingItem(
                    title = stringResource(R.string.font_weight_text),
                    selectedValue = fontWeight.toString(),
                    displayEntries = weightEntries,
                    entryValues = arrayOf("400", "700", "300"),
                    onValueChange = { fontWeight = it.toIntOrNull() ?: 400 },
                )

                // Italic
                TinySwitchSettingItem(
                    title = stringResource(R.string.read_config_italic),
                    checked = isItalic,
                    onCheckedChange = { isItalic = it },
                )

                // Font size offset
                TinySliderSettingItem(
                    title = stringResource(R.string.font_size_offset),
                    value = fontSizeOffset.toFloat(),
                    valueRange = -10f..10f,
                    steps = 19,
                    description = if (fontSizeOffset == 0) {
                        stringResource(R.string.text_default)
                    } else {
                        stringResource(R.string.font_size_offset_value, fontSizeOffset)
                    },
                    onValueChange = { fontSizeOffset = it.toInt() },
                )

                // Underline — 「无」即原来的关闭，选中具体线型即原来的开启；下拉常驻，细节项按选中态展开
                val underlineEntries = arrayOf(
                    stringResource(R.string.underline_none),
                    stringResource(R.string.underline_solid),
                    stringResource(R.string.underline_dashed),
                    stringResource(R.string.underline_wave),
                    stringResource(R.string.underline_title_bar),
                    stringResource(R.string.underline_svg),
                    stringResource(R.string.bookmark_mark_effect_strike),
                    stringResource(R.string.bookmark_mark_effect_highlight),
                )
                val underlineValues = arrayOf("0", "1", "2", "3", "4", "5", "6", "7")
                TinyDropdownSettingItem(
                    title = stringResource(R.string.underline_style),
                    selectedValue = underlineMode.toString(),
                    displayEntries = underlineEntries,
                    entryValues = underlineValues,
                    onValueChange = { underlineMode = it.toIntOrNull() ?: 0 },
                )
                AnimatedVisibility(visible = underlineMode > 0) {
                    Column {
                        // 哪些参数对当前线型真的有效果，由共享几何判定，和正文渲染同一口径。
                        // 荧光(7) 是铺下半行的填充色带、删除线(6) 固定在行高 52%，
                        // 宽度/偏移/圆头/羽化对它们都是死参数，露出来只会让人白调。
                        val support = underlineControlSupport(underlineMode)
                        AnimatedVisibility(visible = underlineMode == 5) {
                            AppTextField(
                                value = underlineSvgPath,
                                onValueChange = { underlineSvgPath = it },
                                label = stringResource(R.string.svg_path),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        TinyClearColorModeSettingItem(
                            title = stringResource(R.string.underline_color),
                            dayColor = underlineColor,
                            nightColor = underlineColorNight,
                            onClickColor = { isNight ->
                                if (isNight) showUnderlineColorNightPicker = true
                                else showUnderlineColorPicker = true
                            },
                            onClearColor = { _ ->
                                // 刷新：同时清除日间色和夜间色
                                underlineColor = null
                                underlineColorNight = null
                            },
                        )

                        AnimatedVisibility(visible = support.width) {
                            TinySliderSettingItem(
                                title = stringResource(R.string.underline_width),
                                value = underlineWidth,
                                // 上限与 HighlightRuleRepository.sanitizeRule 的
                                // coerceIn(0.1f, 10f) 一致：滑块放到 20 会让用户拖到
                                // 10dp 以上，保存时被静默压回 10dp 且无任何提示
                                valueRange = 0.1f..10f,
                                description = String.format("%.1f dp", underlineWidth),
                                onValueChange = { underlineWidth = (it * 10).toInt() / 10f },
                                onReset = { underlineWidth = 1f },
                            )
                        }

                        AnimatedVisibility(visible = support.offset) {
                            TinySliderSettingItem(
                                title = stringResource(R.string.underline_offset),
                                value = underlineOffset,
                                // 同上，与 sanitizeRule 的 coerceIn(-10f, 20f) 对齐
                                valueRange = -10f..20f,
                                description = String.format("%+.1f dp", underlineOffset),
                                onValueChange = { underlineOffset = (it * 10).toInt() / 10f },
                                onReset = { underlineOffset = 2f },
                            )
                        }

                        AnimatedVisibility(visible = support.layer) {
                            TinySwitchSettingItem(
                                title = stringResource(R.string.underline_below_text),
                                checked = underlineBelowText,
                                onCheckedChange = { underlineBelowText = it },
                            )
                        }

                        AnimatedVisibility(visible = support.roundCap) {
                            TinySwitchSettingItem(
                                title = stringResource(R.string.underline_round_cap),
                                checked = underlineRoundCap,
                                onCheckedChange = { underlineRoundCap = it },
                            )
                        }

                        AnimatedVisibility(visible = support.feather) {
                            TinySliderSettingItem(
                                title = stringResource(R.string.underline_feather),
                                value = underlineFeather,
                                valueRange = 0f..5f,
                                description = String.format("%.1f dp", underlineFeather),
                                onValueChange = { underlineFeather = (it * 10).toInt() / 10f },
                            )
                        }

                        AnimatedVisibility(visible = support.dashPattern) {
                            Column {
                                TinySliderSettingItem(
                                    title = stringResource(R.string.underline_dash_len),
                                    value = underlineDashLen,
                                    valueRange = 0f..20f,
                                    description = String.format("%.1f dp", underlineDashLen),
                                    onValueChange = { underlineDashLen = (it * 10).toInt() / 10f },
                                    onReset = { underlineDashLen = 8f },
                                )
                                TinySliderSettingItem(
                                    title = stringResource(R.string.underline_dash_gap),
                                    value = underlineDashGap,
                                    valueRange = 0f..20f,
                                    description = String.format("%.1f dp", underlineDashGap),
                                    onValueChange = { underlineDashGap = (it * 10).toInt() / 10f },
                                    onReset = { underlineDashGap = 5f },
                                )
                            }
                        }

                        // 波浪形状：峰高是实际画出来的高度（渲染时控制点取 2 倍），
                        // 波长是一个完整「上-下」周期。默认值与迁移前一致。
                        AnimatedVisibility(visible = support.waveShape) {
                            Column {
                                TinySliderSettingItem(
                                    title = stringResource(R.string.underline_wave_peak),
                                    value = underlineWavePeak,
                                    valueRange = 0.5f..12f,
                                    description = String.format("%.1f dp", underlineWavePeak),
                                    onValueChange = { underlineWavePeak = (it * 10).toInt() / 10f },
                                    onReset = { underlineWavePeak = HighlightRule.DEFAULT_WAVE_PEAK_DP },
                                )
                                TinySliderSettingItem(
                                    title = stringResource(R.string.underline_wave_length),
                                    value = underlineWaveLength,
                                    valueRange = 4f..60f,
                                    description = String.format("%.1f dp", underlineWaveLength),
                                    onValueChange = { underlineWaveLength = (it * 2).toInt() / 2f },
                                    onReset = { underlineWaveLength = HighlightRule.DEFAULT_WAVE_LENGTH_DP },
                                )
                            }
                        }
                    }
                }

                // Background color
                TinyClearColorModeSettingItem(
                    title = stringResource(R.string.bg_color),
                    dayColor = bgColor,
                    nightColor = bgColorNight,
                    onClickColor = { isNight ->
                        if (isNight) showBgColorNightPicker = true
                        else showBgColorPicker = true
                    },
                    onClearColor = { _ ->
                        // 刷新：同时清除日间色和夜间色
                        bgColor = null
                        bgColorNight = null
                    },
                )

                // Background image
                TinySwitchSettingItem(
                    title = stringResource(R.string.highlight_bg_image),
                    checked = hasBgImage,
                    onCheckedChange = { hasBgImage = it },
                )
                AnimatedVisibility(visible = hasBgImage) {
                    TinyClickableSettingItem(
                        title = stringResource(R.string.highlight_bg_image),
                        description = bgImage.ifBlank { null }?.let { File(it).name },
                        onClick = {
                            imagePicker.launch()
                        },
                    )
                }
                AnimatedVisibility(visible = hasBgImage && bgImage.isNotBlank()) {
                    Column {
                        val fitEntries = arrayOf(
                            stringResource(R.string.bg_fit_tile),
                            stringResource(R.string.bg_fit_stretch),
                            stringResource(R.string.bg_fit_crop),
                            stringResource(R.string.bg_fit_nine_patch),
                        )
                        val fitValues = arrayOf("0", "1", "2", "3")
                        TinyDropdownSettingItem(
                            title = stringResource(R.string.bg_image_fit),
                            selectedValue = bgImageFit.toString(),
                            displayEntries = fitEntries,
                            entryValues = fitValues,
                            onValueChange = {
                                val newFit = it.toIntOrNull() ?: 0
                                bgImageFit = newFit
                                if (newFit == 3) {
                                    showNinePatchEditor = true
                                }
                            },
                        )

                        AnimatedVisibility(visible = bgImageFit == 3) {
                            Column {
                                TinyClickableSettingItem(
                                    title = "内边距",
                                    description = String.format("左%.0f 右%.0f 上%.0f 下%.0f", bgPaddingStart, bgPaddingEnd, bgPaddingTop, bgPaddingBottom),
                                    onClick = { showInsetEditor = !showInsetEditor },
                                )
                                AnimatedVisibility(visible = showInsetEditor) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        // 一行四个按钮，点击切换哪个方向的滑块
                                        var activeInset by remember { mutableIntStateOf(-1) }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf("左" to 0, "右" to 1, "上" to 2, "下" to 3).forEach { (label, idx) ->
                                                val selected = activeInset == idx
                                                val values = listOf(bgPaddingStart, bgPaddingEnd, bgPaddingTop, bgPaddingBottom)
                                                NormalCard(
                                                    onClick = { activeInset = if (selected) -1 else idx },
                                                    containerColor = if (selected) LegadoTheme.colorScheme.secondaryContainer
                                                        else LegadoTheme.colorScheme.surfaceContainerLow,
                                                    cornerRadius = 8.dp,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    AppText(
                                                        "$label ${values[idx].toInt()}",
                                                        style = LegadoTheme.typography.labelSmall,
                                                        color = if (selected) LegadoTheme.colorScheme.onSecondaryContainer
                                                            else LegadoTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    )
                                                }
                                            }
                                        }
                                        // 选中方向的滑块
                                        AnimatedVisibility(visible = activeInset >= 0) {
                                            val range = -32f..64f
                                            val currentVal = when (activeInset) {
                                                0 -> bgPaddingStart; 1 -> bgPaddingEnd
                                                2 -> bgPaddingTop; else -> bgPaddingBottom
                                            }
                                            val updateInset: (Float) -> Unit = { v ->
                                                val rounded = v.toInt().toFloat()
                                                when (activeInset) {
                                                    0 -> bgPaddingStart = rounded
                                                    1 -> bgPaddingEnd = rounded
                                                    2 -> bgPaddingTop = rounded
                                                    else -> bgPaddingBottom = rounded
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Slider(
                                                    value = currentVal,
                                                    onValueChange = updateInset,
                                                    valueRange = range,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                // 步进按钮：1dp 精调，滑块粗调
                                                ValueStepper(
                                                    value = currentVal,
                                                    displayValue = currentVal,
                                                    valueRange = range,
                                                    onValueChange = updateInset,
                                                    stepSize = 1f,
                                                )
                                            }
                                        }
                                    }
                                }
                                TinyClickableSettingItem(
                                    title = "外边距",
                                    description = String.format("左%.0f 右%.0f 上%.0f 下%.0f", bgMarginStart, bgMarginEnd, bgMarginTop, bgMarginBottom),
                                    onClick = { showMarginEditor = !showMarginEditor },
                                )
                                AnimatedVisibility(visible = showMarginEditor) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        var activeMargin by remember { mutableIntStateOf(-1) }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf("左" to 0, "右" to 1, "上" to 2, "下" to 3).forEach { (label, idx) ->
                                                val selected = activeMargin == idx
                                                val values = listOf(bgMarginStart, bgMarginEnd, bgMarginTop, bgMarginBottom)
                                                NormalCard(
                                                    onClick = { activeMargin = if (selected) -1 else idx },
                                                    containerColor = if (selected) LegadoTheme.colorScheme.secondaryContainer
                                                        else LegadoTheme.colorScheme.surfaceContainerLow,
                                                    cornerRadius = 8.dp,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    AppText(
                                                        "$label ${values[idx].toInt()}",
                                                        style = LegadoTheme.typography.labelSmall,
                                                        color = if (selected) LegadoTheme.colorScheme.onSecondaryContainer
                                                            else LegadoTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    )
                                                }
                                            }
                                        }
                                        AnimatedVisibility(visible = activeMargin >= 0) {
                                            val currentVal = when (activeMargin) {
                                                0 -> bgMarginStart; 1 -> bgMarginEnd
                                                2 -> bgMarginTop; else -> bgMarginBottom
                                            }
                                            val updateMargin: (Float) -> Unit = { v ->
                                                val rounded = v.toInt().toFloat()
                                                when (activeMargin) {
                                                    0 -> bgMarginStart = rounded
                                                    1 -> bgMarginEnd = rounded
                                                    2 -> bgMarginTop = rounded
                                                    else -> bgMarginBottom = rounded
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Slider(
                                                    value = currentVal,
                                                    onValueChange = updateMargin,
                                                    valueRange = -16f..64f,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                // 步进按钮：1dp 精调，滑块粗调
                                                ValueStepper(
                                                    value = currentVal,
                                                    displayValue = currentVal,
                                                    valueRange = -16f..64f,
                                                    onValueChange = updateMargin,
                                                    stepSize = 1f,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // === Section 3: Config Binding ===
                if (allConfigNames.isNotEmpty()) {
                    SectionTitle("应用排版")
                    LazyRow(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Global toggle
                        item {
                            val selected = configNames.isEmpty()
                            val bg = if (selected) LegadoTheme.colorScheme.secondaryContainer
                            else LegadoTheme.colorScheme.surfaceContainerLow
                            val fg = if (selected) LegadoTheme.colorScheme.onSecondaryContainer
                            else LegadoTheme.colorScheme.onSurfaceVariant
                            NormalCard(
                                onClick = { configNames = emptySet() },
                                containerColor = bg,
                                cornerRadius = 8.dp,
                            ) {
                                AppText(
                                    "全局",
                                    style = LegadoTheme.typography.labelMedium,
                                    color = fg,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        itemsIndexed(allConfigNames) { _, cn ->
                            val selected = cn in configNames
                            val bg = if (selected) LegadoTheme.colorScheme.secondaryContainer
                            else LegadoTheme.colorScheme.surfaceContainerLow
                            val fg = if (selected) LegadoTheme.colorScheme.onSecondaryContainer
                            else LegadoTheme.colorScheme.onSurfaceVariant
                            NormalCard(
                                onClick = {
                                    configNames = if (selected) configNames - cn
                                    else configNames + cn
                                },
                                containerColor = bg,
                                cornerRadius = 8.dp,
                            ) {
                                AppText(
                                    cn,
                                    style = LegadoTheme.typography.labelMedium,
                                    color = fg,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

            }

            // === 停靠预览区（原 Section 5）===
            // 预览常驻弹层底部：调上面的正则、颜色、下划线、九宫格时不用滚到末尾也能看到效果。
            // 折叠开关在标题栏左侧（与「保存」对称），这里只在展开时占高度。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                // 折叠时留一条分隔线，让「底部有个可展开的面板」这件事仍然看得出来
                HorizontalDivider(color = LegadoTheme.colorScheme.outlineVariant)
                if (previewDockExpanded) {
                    Spacer(Modifier.height(8.dp))
                    // 停靠区高度有限：示例文本按 4 行封顶，再长就在框里滚动，不能把上方的表单挤没。
                    // 不给 label——标题栏已有「预览效果」说明这里是什么，浮动标签白占一行高度；
                    // 改用 placeholder，只在空内容时出现，输入后不占位。
                    // 字号跟卡片里的预览一致（同为 previewBaseFontSize = 正文字号）：
                    // 输入框与它下面那张卡片本来就是同一段文字的两种呈现，字号不一致
                    // 看起来像两个不相干的东西。字距也对齐，否则汉字间距会差一截。
                    val sampleTextStyle = remember(previewBaseFontSize, ReadBookConfig.letterSpacing) {
                        TextStyle(
                            fontSize = previewBaseFontSize.sp,
                            letterSpacing = ReadBookConfig.letterSpacing.em,
                        )
                    }
                    AppTextField(
                        value = sampleText,
                        onValueChange = { sampleText = it },
                        placeholder = {
                            AppText(
                                text = stringResource(R.string.sample_text),
                                style = sampleTextStyle,
                            )
                        },
                        maxLines = 4,
                        textStyle = sampleTextStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // 预览页面底色跟随「应用排版」绑定的排版；「全局」没绑定具体排版，用当前正在用的那份
                    val previewConfig = remember(configNames) {
                        configNames.firstNotNullOfOrNull { ReadBookConfig.configByName(it) }
                            ?: ReadBookConfig.durConfig
                    }
                    val previewDayBgImage = pageBgImagePathOf(previewConfig.bgType, previewConfig.bgStr)
                    val previewDayBgColor = if (previewDayBgImage == null) {
                        previewConfig.bgStr.toPreviewColor(0xFFEEEEEE.toInt())
                    } else {
                        0xFFEEEEEE.toInt()
                    }
                    // 夜间预览：夜间色优先，否则从日间色派生
                    val nightTextColor = textColorNight ?: textColor?.let { ColorUtils.flipLightness(it) }
                    val nightBgColor = bgColorNight ?: bgColor?.let { ColorUtils.flipLightness(it) }
                    val nightUnderlineColor = if (underlineMode > 0) {
                        underlineColorNight ?: underlineColor?.let { ColorUtils.flipLightness(it) }
                    } else null
                    val previewNightBgImage = pageBgImagePathOf(
                        previewConfig.bgTypeNight, previewConfig.bgStrNight
                    )
                    val previewNightBgColor = if (previewNightBgImage == null) {
                        previewConfig.bgStrNight.toPreviewColor(0xFF000000.toInt())
                    } else {
                        0xFF000000.toInt()
                    }

                    // 日、夜两块共用同一批样式参数，只有排版底色与色板不同，抽成本地 composable
                    // 免得把这三十来个参数抄两遍。卡片内不画标题（label 传 null），
                    // 靠底色深浅区分日夜，省下的高度留给表单。
                    @Composable
                    fun DayPreview() {
                        HighlightRulePreview(
                            // 不画「日间」标题：日夜上下相邻，靠底色深浅就能分辨
                            label = null,
                            sampleText = sampleText,
                            pattern = pattern,
                            textColor = textColor,
                            bgColor = bgColor,
                            bgImage = if (hasBgImage) bgImage else "",
                            bgImageFit = bgImageFit,
                            bgImageScale = bgImageScale,
                            underlineMode = underlineMode,
                            underlineColor = if (underlineMode > 0) underlineColor else null,
                            underlineWidth = underlineWidth,
                            underlineOffset = underlineOffset,
                            underlineSvgPath = underlineSvgPath,
                            pageBgColor = previewDayBgColor,
                            pageTextColor = previewConfig.getTextColor().toPreviewColor(0xFF3E3D3B.toInt()),
                            pageBgImagePath = previewDayBgImage,
                            npLeft = effectiveNineSlice.left,
                            npRight = effectiveNineSlice.right,
                            npTop = effectiveNineSlice.top,
                            npBottom = effectiveNineSlice.bottom,
                            bgPadStart = bgPaddingStart,
                            bgPadEnd = bgPaddingEnd,
                            bgPadTop = bgPaddingTop,
                            bgPadBottom = bgPaddingBottom,
                            bgMarginStart = bgMarginStart,
                            bgMarginEnd = bgMarginEnd,
                            bgMarginTop = bgMarginTop,
                            bgMarginBottom = bgMarginBottom,
                            fontSizeOffset = fontSizeOffset,
                            fontWeight = fontWeight,
                            isItalic = isItalic,
                            underlineBelowText = underlineBelowText,
                            underlineRoundCap = underlineRoundCap,
                            underlineFeather = underlineFeather,
                            underlineDashLen = underlineDashLen,
                            underlineDashGap = underlineDashGap,
                            underlineWavePeak = underlineWavePeak,
                            underlineWaveLength = underlineWaveLength,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    @Composable
                    fun NightPreview() {
                        HighlightRulePreview(
                            // 同上，不画「夜间」标题
                            label = null,
                            sampleText = sampleText,
                            pattern = pattern,
                            textColor = nightTextColor,
                            bgColor = nightBgColor,
                            bgImage = "",
                            bgImageFit = bgImageFit,
                            bgImageScale = bgImageScale,
                            underlineMode = underlineMode,
                            underlineColor = nightUnderlineColor,
                            underlineWidth = underlineWidth,
                            underlineOffset = underlineOffset,
                            underlineSvgPath = underlineSvgPath,
                            pageBgColor = previewNightBgColor,
                            pageTextColor = previewConfig.getTextColorNight()
                                .toPreviewColor(0xFFADADAD.toInt()),
                            pageBgImagePath = previewNightBgImage,
                            npLeft = npLeft,
                            npRight = npRight,
                            npTop = npTop,
                            npBottom = npBottom,
                            bgPadStart = bgPaddingStart,
                            bgPadEnd = bgPaddingEnd,
                            bgPadTop = bgPaddingTop,
                            bgPadBottom = bgPaddingBottom,
                            bgMarginStart = bgMarginStart,
                            bgMarginEnd = bgMarginEnd,
                            bgMarginTop = bgMarginTop,
                            bgMarginBottom = bgMarginBottom,
                            fontSizeOffset = fontSizeOffset,
                            fontWeight = fontWeight,
                            isItalic = isItalic,
                            underlineBelowText = underlineBelowText,
                            underlineRoundCap = underlineRoundCap,
                            underlineFeather = underlineFeather,
                            underlineDashLen = underlineDashLen,
                            underlineDashGap = underlineDashGap,
                            underlineWavePeak = underlineWavePeak,
                            underlineWaveLength = underlineWaveLength,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 日夜上下堆叠、各占满宽：并排会把卡片压到半宽，
                    // 下划线、九宫格这类以行高为锚的效果在半宽里看不出真实表现。
                    // 配了高亮背景图时正文只用那张图，夜间色不参与渲染，这时只显示日间。
                    DayPreview()
                    if (!hasBgImage || bgImage.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        NightPreview()
                    }
                }
            }
        }
    }

    // Color pickers
    ColorPickerSheet(
        show = showTextColorPicker,
        initialColor = textColor ?: 0,
        onDismissRequest = { showTextColorPicker = false },
        onColorSelected = { color ->
            textColor = color
            // 自动派生夜间色（如果夜间色未被用户手动设置过）
            if (textColorNight == null) {
                textColorNight = ColorUtils.flipLightness(color)
            }
            showTextColorPicker = false
        },
    )
    ColorPickerSheet(
        show = showBgColorPicker,
        initialColor = bgColor ?: 0,
        onDismissRequest = { showBgColorPicker = false },
        onColorSelected = { color ->
            bgColor = color
            // 自动派生夜间色（如果夜间色未被用户手动设置过）
            if (bgColorNight == null) {
                bgColorNight = ColorUtils.flipLightness(color)
            }
            showBgColorPicker = false
        },
    )
    ColorPickerSheet(
        show = showUnderlineColorPicker,
        initialColor = underlineColor ?: 0,
        onDismissRequest = { showUnderlineColorPicker = false },
        onColorSelected = { color ->
            underlineColor = color
            // 自动派生夜间色（如果夜间色未被用户手动设置过）
            if (underlineColorNight == null) {
                underlineColorNight = ColorUtils.flipLightness(color)
            }
            showUnderlineColorPicker = false
        },
    )
    // Night color pickers
    ColorPickerSheet(
        show = showTextColorNightPicker,
        initialColor = textColorNight ?: textColor?.let { ColorUtils.flipLightness(it) } ?: 0,
        onDismissRequest = { showTextColorNightPicker = false },
        onColorSelected = { color ->
            textColorNight = color
            // 自动派生日间色（如果日间色未被用户手动设置过）
            if (textColor == null) {
                textColor = ColorUtils.flipLightness(color)
            }
            showTextColorNightPicker = false
        },
    )
    ColorPickerSheet(
        show = showBgColorNightPicker,
        initialColor = bgColorNight ?: bgColor?.let { ColorUtils.flipLightness(it) } ?: 0,
        onDismissRequest = { showBgColorNightPicker = false },
        onColorSelected = { color ->
            bgColorNight = color
            // 自动派生日间色（如果日间色未被用户手动设置过）
            if (bgColor == null) {
                bgColor = ColorUtils.flipLightness(color)
            }
            showBgColorNightPicker = false
        },
    )
    ColorPickerSheet(
        show = showUnderlineColorNightPicker,
        initialColor = underlineColorNight ?: underlineColor?.let { ColorUtils.flipLightness(it) } ?: 0,
        onDismissRequest = { showUnderlineColorNightPicker = false },
        onColorSelected = { color ->
            underlineColorNight = color
            // 自动派生日间色（如果日间色未被用户手动设置过）
            if (underlineColor == null) {
                underlineColor = ColorUtils.flipLightness(color)
            }
            showUnderlineColorNightPicker = false
        },
    )

    // Nine-patch editor
    NinePatchEditorDialog(
        show = showNinePatchEditor,
        imagePath = bgImage,
        // np* 存的是「角块占比」，编辑器内部用「绝对线位置(0~1)」，此处做转换
        initialLeft = effectiveNineSlice.left,
        initialRight = 1f - effectiveNineSlice.right,
        initialTop = effectiveNineSlice.top,
        initialBottom = 1f - effectiveNineSlice.bottom,
        onDismissRequest = { showNinePatchEditor = false },
        onSave = { left, right, top, bottom ->
            npLeft = left
            npRight = 1f - right
            npTop = top
            npBottom = 1f - bottom
            // 只有真的在编辑器里保存过切分线才转手动；选九宫格后直接关编辑器＝保持自动切
            manualNineSlice = true
            showNinePatchEditor = false
        },
    )

    // Font selector
    val readSettingsRepository: ReadSettingsRepository = org.koin.compose.koinInject()
    val fontSelectScope = rememberCoroutineScope()
    val fontSelectPreferences by readSettingsRepository.preferences.collectAsStateWithLifecycle(
        initialValue = null
    )
    val fontFolderState = remember(fontSelectPreferences) {
        val pref = fontSelectPreferences
        if (pref == null) {
            FontFolderState.Loading
        } else {
            FontFolderState.Loaded(pref.fontFolder.takeIf { it.isNotEmpty() }?.toUri())
        }
    }
    val systemTypefaces = stringArrayResource(R.array.system_typefaces)
    val fontFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            fontSelectScope.launch {
                readSettingsRepository.setFontFolder(it.toString())
            }
        }
    }
    FontSelectSheet(
        show = showFontSelect,
        title = stringResource(R.string.select_font),
        folderState = fontFolderState,
        selectedFontPath = fontPath,
        onDismissRequest = { showFontSelect = false },
        onSelectFont = { fontPath = it.uri.toString(); showFontSelect = false },
        onSelectSystemTypeface = { fontPath = ""; showFontSelect = false },
        onOpenFolderPicker = { fontFolderLauncher.launch(null) },
        systemTypefaces = systemTypefaces,
    )
}

private val previewBaseFontSize: Int get() = ReadBookConfig.textSize

/** 预览排版分段：文本（可能插入外边距占位空格）、偏移后的命中区间、占位描述。 */
private data class MarginSegmentation(
    val text: String,
    val ranges: List<IntRange>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
)

/** 排版背景图的加载地址；bgType 0 是纯色，1 是 assets 内置图，2 是外部图片 */
private fun pageBgImagePathOf(bgType: Int, bgStr: String): String? = when (bgType) {
    1 -> "file:///android_asset/bg/$bgStr"
    2 -> ReadStyleResolver.backgroundPath(bgType, bgStr)
    else -> null
}

/** 颜色字符串解析不出来（比如 bgStr 存的是图片文件名）时退回 fallback */
private fun String?.toPreviewColor(fallback: Int): Int =
    this?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: fallback

/** 预览实际生效的九宫格切分线（自动探测优先时与正文渲染同一口径） */
private data class NineSliceValues(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

@Composable
internal fun HighlightRulePreview(
    /** 卡片内的标题行。传 null 不画——并排停靠时靠底色深浅就能分清日夜，省一行是一行。 */
    label: String?,
    sampleText: String,
    pattern: String,
    textColor: Int?,
    bgColor: Int?,
    bgImage: String,
    bgImageFit: Int,
    bgImageScale: Float,
    underlineMode: Int,
    underlineColor: Int?,
    underlineWidth: Float,
    underlineOffset: Float,
    /** 自定义 SVG 下划线的 pathData，只有 underlineMode == 5 会用。 */
    underlineSvgPath: String = "",
    pageBgColor: Int,
    pageTextColor: Int,
    /** 排版背景是图片时的加载地址，为 null 表示纯色背景 */
    pageBgImagePath: String? = null,
    npLeft: Float = 0.5f,
    npRight: Float = 0.5f,
    npTop: Float = 0.5f,
    npBottom: Float = 0.5f,
    bgPadStart: Float = 0f,
    bgPadEnd: Float = 0f,
    bgPadTop: Float = 0f,
    bgPadBottom: Float = 0f,
    bgMarginStart: Float = 0f,
    bgMarginEnd: Float = 0f,
    bgMarginTop: Float = 0f,
    bgMarginBottom: Float = 0f,
    fontSizeOffset: Int = 0,
    fontWeight: Int = 400,
    isItalic: Boolean = false,
    underlineBelowText: Boolean = false,
    underlineRoundCap: Boolean = false,
    underlineFeather: Float = 0f,
    underlineDashLen: Float = 8f,
    underlineDashGap: Float = 5f,
    /** 波浪实际峰高（dp）。渲染时控制点取 2 倍，见 drawUnderlineSegment。 */
    underlineWavePeak: Float = 1.5f,
    /** 波浪波长（dp，一个完整「上-下」周期）。 */
    underlineWaveLength: Float = 24f,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val defaultTextColor = Color(pageTextColor)
    val resolvedTextColor = textColor?.let { Color(it) } ?: defaultTextColor
    val resolvedUnderlineColor = underlineColor?.let { Color(it) } ?: resolvedTextColor

    // 加载背景图 Bitmap，原始 Bitmap 供九宫格绘制使用
    val bgRawBitmap = remember(bgImage) {
        if (bgImage.isBlank()) null
        else runCatching {
            BitmapFactory.decodeFile(bgImage)
        }.getOrNull()
    }
    val bgBitmap = remember(bgRawBitmap) { bgRawBitmap?.asImageBitmap() }

    // 九宫格绘制用的 Paint
    val ninePatchPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }

    // 真正跑一遍正则，只有命中的片段才应用样式
    val matchRanges = remember(pattern, sampleText) {
        if (pattern.isBlank() || sampleText.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                Regex(pattern).findAll(sampleText)
                    .filter { !it.range.isEmpty() }
                    .map { it.range }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }

    // 外边距预览：引擎只在九宫格（fit==3）背景下消费 bgMargin——背景 run 起点前 x 推进
    // marginStart、终点后推进 marginEnd（负值钳为 0），把命中段前后的文字推开
    // （ReaderPaginator.backgroundMarginBefore/After）。预览在命中段前后插入宽度=外边距的
    // 占位空格复现同一排版：占位本身无字形，背景图/下划线仍画在命中文字矩形上，间隙留在图外。
    // 引擎把相邻的同图文字视作一个 run（中间不产生间隙），先合并贴合的命中区间再插占位。
    val density = LocalDensity.current
    val marginStartWidth = with(density) { bgMarginStart.coerceAtLeast(0f).dp.toSp() }
    val marginEndWidth = with(density) { bgMarginEnd.coerceAtLeast(0f).dp.toSp() }
    val applyBgMargins = bgBitmap != null && bgImageFit == 3 &&
            (marginStartWidth.value > 0f || marginEndWidth.value > 0f)
    val marginSegmentation = remember(
        sampleText, matchRanges, applyBgMargins, marginStartWidth, marginEndWidth
    ) {
        if (!applyBgMargins) {
            MarginSegmentation(sampleText, matchRanges, emptyList())
        } else {
            val runs = ArrayList<IntRange>(matchRanges.size)
            matchRanges.forEach { range ->
                val last = runs.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    runs[runs.lastIndex] = last.first..maxOf(last.last, range.last)
                } else {
                    runs += range
                }
            }
            val builder = StringBuilder(sampleText.length)
            val shifted = ArrayList<IntRange>(runs.size)
            val placeholders = ArrayList<AnnotatedString.Range<Placeholder>>(runs.size * 2)
            var cursor = 0
            runs.forEach { range ->
                builder.append(sampleText, cursor, range.first)
                if (marginStartWidth.value > 0f) {
                    val at = builder.length
                    builder.append(' ')
                    placeholders += AnnotatedString.Range(
                        Placeholder(
                            width = marginStartWidth,
                            height = previewBaseFontSize.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                        at,
                        at + 1,
                    )
                }
                val hitStart = builder.length
                builder.append(sampleText, range.first, range.last + 1)
                shifted += hitStart until builder.length
                if (marginEndWidth.value > 0f) {
                    val at = builder.length
                    builder.append(' ')
                    placeholders += AnnotatedString.Range(
                        Placeholder(
                            width = marginEndWidth,
                            height = previewBaseFontSize.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                        at,
                        at + 1,
                    )
                }
                cursor = range.last + 1
            }
            builder.append(sampleText, cursor, sampleText.length)
            MarginSegmentation(builder.toString(), shifted, placeholders)
        }
    }

    val annotated = remember(
        marginSegmentation, bgBitmap, resolvedTextColor, bgColor, fontWeight, isItalic, fontSizeOffset
    ) {
        buildAnnotatedString {
            append(marginSegmentation.text)
            marginSegmentation.ranges.forEach { range ->
                addStyle(
                    SpanStyle(
                        color = resolvedTextColor,
                        background = if (bgColor != null && bgBitmap == null) Color(bgColor) else Color.Unspecified,
                        fontWeight = when {
                            fontWeight >= 700 -> androidx.compose.ui.text.font.FontWeight.Bold
                            fontWeight <= 300 -> androidx.compose.ui.text.font.FontWeight.Light
                            else -> null
                        },
                        fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                        // 字号偏移只作用于命中文字，不影响整行
                        fontSize = if (fontSizeOffset != 0) {
                            (previewBaseFontSize + fontSizeOffset).sp
                        } else {
                            androidx.compose.ui.unit.TextUnit.Unspecified
                        },
                    ),
                    range.first,
                    (range.last + 1).coerceAtMost(marginSegmentation.text.length),
                )
            }
        }
    }

    val labelColor = if (ColorUtils.isColorLight(pageBgColor)) {
        Color(0x99000000)
    } else {
        Color(0x99FFFFFF)
    }
    // 排版背景是图片时铺在卡片上，纯色时该 painter 为 null
    val pageBgPainter = if (pageBgImagePath != null) {
        rememberAsyncImagePainter(pageBgImagePath)
    } else {
        null
    }
    // 卡片内容的水平内边距：既是 Column 的 padding，也是文本测量要从可用宽度里扣掉的量，
    // 两者必须同源，否则测出来的折行位置和画出来的画布对不上
    val cardContentPadding = 16.dp
    // 文本按卡片真实宽度测量：预览并排停靠后卡片不再满宽，
    // 继续按「屏宽 - 64dp」估算只会让文字按错误的宽度折行、超出画布被裁掉
    BoxWithConstraints(modifier = modifier) {
        val previewConstraintWidth = with(density) {
            val contentWidth = if (maxWidth.isFinite) maxWidth - cardContentPadding * 2 else maxWidth
            contentWidth.coerceAtLeast(0.dp).roundToPx()
        }
        NormalCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
            containerColor = Color(pageBgColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (pageBgPainter != null) {
                            // 不用 Modifier.paint：它在高度无界的滚动容器里会拿图片固有尺寸
                            // 反算 minHeight，把卡片撑成整张图那么高（sizeToIntrinsics=false 也挡不住，
                            // 那个分支还要求宽高都有界）。drawBehind 只画不测量，尺寸仍由文字决定。
                            Modifier.drawBehind {
                                val src = pageBgPainter.intrinsicSize
                                if (!src.isSpecified || src.width <= 0f || src.height <= 0f) {
                                    return@drawBehind
                                }
                                // 等比放大到铺满后居中裁切，等价于 ContentScale.Crop
                                val factor = ContentScale.Crop.computeScaleFactor(src, size)
                                val dst = Size(src.width * factor.scaleX, src.height * factor.scaleY)
                                clipRect {
                                    translate(
                                        (size.width - dst.width) / 2f,
                                        (size.height - dst.height) / 2f,
                                    ) {
                                        with(pageBgPainter) { draw(dst) }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = cardContentPadding, vertical = 12.dp)
            ) {
                if (!label.isNullOrEmpty()) {
                    AppText(
                        text = label,
                        style = LegadoTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (pattern.isNotBlank() && matchRanges.isEmpty()) {
                    AppText(
                        text = stringResource(R.string.highlight_preview_no_match),
                        style = LegadoTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // 与正文 ChapterProvider.contentPaint 同口径计算行高（descent - ascent + leading）：
                // Compose 默认行高会取 CJK 回退字体的高度量，比正文 Paint 的 fontMetrics 高，
                // 九宫格这类以行高为锚的背景会因此预览偏大、正文偏小，必须强制对齐
                val bodyLineHeight = remember(previewBaseFontSize) {
                    val textBoldWeight = when (val bold = ReadBookConfig.textBold) {
                        1 -> 900
                        2 -> 300
                        0 -> 400
                        in 100..900 -> bold
                        else -> 400
                    }
                    android.text.TextPaint().apply {
                        textSize = with(density) { previewBaseFontSize.sp.toPx() }
                        letterSpacing = ReadBookConfig.letterSpacing
                        typeface = ReaderAndroidPaintFactory
                            .loadTypeface(ReadBookConfig.textFont, textBoldWeight, false)
                    }.textHeight
                }
                val previewTextResult = textMeasurer.measure(
                    text = annotated,
                    style = TextStyle(
                        fontSize = previewBaseFontSize.sp,
                        color = defaultTextColor,
                        lineHeight = with(density) { bodyLineHeight.toSp() },
                        letterSpacing = ReadBookConfig.letterSpacing.em,
                    ),
                    maxLines = 5,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = previewConstraintWidth),
                    placeholders = marginSegmentation.placeholders,
                )
                // 九宫格背景会向外扩角块与 padding，超出行高的部分要给 Canvas 预留空间，否则预览被裁掉
                var ninePatchTopOverhang = 0f
                var ninePatchBottomOverhang = 0f
                if (bgImageFit == 3 && bgRawBitmap != null) {
                    val maxLineHeight = (0 until previewTextResult.lineCount)
                        .maxOf { previewTextResult.getLineBottom(it) - previewTextResult.getLineTop(it) }
                        .coerceAtLeast(1f)
                    // 用足够宽的矩形探测：角块只在文字放不下时才缩小，宽矩形给出上下外扩的最大值
                    val probeBox = io.legado.app.help.highlight.NinePatchDrawHelper.layout(
                        0f, 0f, 10000f, maxLineHeight,
                        bgRawBitmap.width.toFloat(), bgRawBitmap.height.toFloat(),
                        npLeft, npRight, npTop, npBottom,
                        bgPadStart * density.density, bgPadEnd * density.density,
                        bgPadTop * density.density, bgPadBottom * density.density,
                    )
                    if (probeBox != null) {
                        ninePatchTopOverhang = -probeBox.top
                        ninePatchBottomOverhang = probeBox.bottom - maxLineHeight
                    }
                }
                // 下划线画在行底 + offset 处，圆头/羽化/双线/波浪还要向下延伸，
                // 画布按最大外扩预留，否则贴底时整条线连同柔边一起被裁掉。
                // extra 与正文 overflowPadPx 同一口径：波浪按实际峰高（控制点的一半），
                // 双线按净间隙 + 线宽。荧光(7) 是行盒内的填充色带，不需要额外外扩。
                val underlineBottomOverhangPx = if (underlineMode in 1..5) {
                    with(density) {
                        val extra = when (underlineMode) {
                            3 -> underlineWavePeak.dp.toPx()
                            4 -> READER_DOUBLE_LINE_GAP_DP.dp.toPx() + underlineWidth.dp.toPx()
                            else -> 0f
                        }
                        underlineOffset.dp.toPx().coerceAtLeast(0f) +
                            underlineWidth.dp.toPx() / 2f +
                            underlineFeather.dp.toPx() + extra
                    }
                } else {
                    0f
                }
                val canvasHeightDp = with(density) {
                    (previewTextResult.size.height + ninePatchTopOverhang +
                        maxOf(ninePatchBottomOverhang, underlineBottomOverhangPx)).toDp()
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(canvasHeightDp)
                ) {
                    // 九宫格外扩部分超出文本区，整体下移让上角块完整显示（Canvas 高度已预留）
                    if (ninePatchTopOverhang != 0f) {
                        drawContext.canvas.translate(0f, ninePatchTopOverhang)
                    }

                    // 在匹配区域画背景图（区间已按外边距占位偏移，指向占位后的命中文字）
                    if (bgBitmap != null && marginSegmentation.ranges.isNotEmpty()) {
                        val density = this.density
                        marginSegmentation.ranges.forEach { range ->
                            val start = range.first
                            val endExclusive = (range.last + 1).coerceAtMost(marginSegmentation.text.length)
                            previewTextResult.forEachLineSegment(start, endExclusive) { rectL, rectR, rectT, rectB, _ ->
                                if (bgImageFit == 3 && bgRawBitmap != null) {
                                    // 与渲染层同一套几何（NinePatchDrawHelper.layout）：
                                    // 以行高为锚算四角并外扩背景框，文字落在中段拉伸区内；
                                    // 渲染层矩形上下各内缩 1dp（TextLine.bgPaddingTop/Bottom），这里保持一致
                                    val nineSliceInset = minOf(1.dp.toPx(), (rectB - rectT) / 4f)
                                    io.legado.app.help.highlight.NinePatchDrawHelper.layout(
                                        rectL, rectT + nineSliceInset, rectR, rectB - nineSliceInset,
                                        bgRawBitmap.width.toFloat(), bgRawBitmap.height.toFloat(),
                                        npLeft, npRight, npTop, npBottom,
                                        bgPadStart * density, bgPadEnd * density,
                                        bgPadTop * density, bgPadBottom * density,
                                    )?.let { box ->
                                        io.legado.app.help.highlight.NinePatchDrawHelper.draw(
                                            drawContext.canvas.nativeCanvas,
                                            bgRawBitmap,
                                            box.left, box.top, box.right, box.bottom,
                                            ninePatchPaint,
                                            leftX = npLeft, rightX = 1f - npRight,
                                            topY = npTop, bottomY = 1f - npBottom,
                                            box.cornerL, box.cornerR, box.cornerT, box.cornerB,
                                        )
                                    }
                                } else {
                                    drawImage(
                                        image = bgBitmap,
                                        dstOffset = androidx.compose.ui.unit.IntOffset(rectL.toInt(), rectT.toInt()),
                                        dstSize = androidx.compose.ui.unit.IntSize(
                                            (rectR - rectL).toInt().coerceAtLeast(1),
                                            (rectB - rectT).toInt().coerceAtLeast(1)
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // 下划线绘制块（可在文字上层或下层）
                    val drawUnderlinesBlock: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                        if (underlineMode > 0) {
                            marginSegmentation.ranges.forEach { range ->
                                val start = range.first
                                val endExclusive = (range.last + 1).coerceAtMost(marginSegmentation.text.length)
                                previewTextResult.forEachLineSegment(start, endExclusive) { left, right, top, bottom, _ ->
                                    when (underlineMode) {
                                        // 7 荧光：不是下划线，是铺下半行的填充色带（正文
                                        // ReaderHalfHighlightDrawCommand 同口径）。宽度/偏移/圆头/
                                        // 羽化对它无效，只有颜色生效，透明度用颜色自带的 alpha，
                                        // 不额外压暗（对照 MarkingEffect.toStyle 的口径）。
                                        7 -> drawFluorescentBand(
                                            left = left,
                                            right = right,
                                            top = top,
                                            bottom = bottom,
                                            color = resolvedUnderlineColor,
                                            roundCap = underlineRoundCap,
                                            feather = if (underlineControlSupport(7).feather) underlineFeather else 0f,
                                        )

                                        // 6 删除线：固定落在行高 READER_STRIKE_HEIGHT_RATIO 处，不吃偏移；
                                        // 圆头与羽化对该线型不开放，这里显式不传，免得旧数据
                                        // 里存的值画出与正文不同的效果
                                        6 -> drawUnderlineSegment(
                                            mode = 1,
                                            color = resolvedUnderlineColor,
                                            widthDp = underlineWidth,
                                            startX = left,
                                            endX = right,
                                            y = top + (bottom - top) * READER_STRIKE_HEIGHT_RATIO,
                                        )

                                        else -> drawUnderlineSegment(
                                            mode = underlineMode,
                                            color = resolvedUnderlineColor,
                                            widthDp = underlineWidth,
                                            startX = left,
                                            endX = right,
                                            y = bottom + underlineOffset.dp.toPx(),
                                            // 圆头与羽化都按线型适用性取：双实线/删除线/自定义 SVG 不开放这两项，
                                            // 旧数据里存的值也不该在这里画出来。正文侧走 ReaderUnderline 的
                                            // roundCapEffective / featherEffective，两边读同一份 underlineControlSupport
                                            roundCap = underlineControlSupport(underlineMode).roundCap &&
                                                    underlineRoundCap,
                                            feather = if (underlineControlSupport(underlineMode).feather) {
                                                underlineFeather
                                            } else {
                                                0f
                                            },
                                            dashLen = underlineDashLen,
                                            dashGap = underlineDashGap,
                                            svgPath = underlineSvgPath,
                                            wavePeakDp = underlineWavePeak,
                                            waveLengthDp = underlineWaveLength,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 荧光色带必须压在文字层之下，与正文渲染一致，不受「下层」开关影响
                    val belowText = underlineBelowText || underlineMode == 7
                    if (belowText) drawUnderlinesBlock()
                    drawText(previewTextResult)
                    if (!belowText) drawUnderlinesBlock()

                    // 恢复画布，避免平移泄漏到后续绘制
                    if (ninePatchTopOverhang != 0f) {
                        drawContext.canvas.translate(0f, -ninePatchTopOverhang)
                    }
                }
            }
        }
    }
}
