package io.legado.app.ui.book.read.sheet

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.data.repository.configNames
import io.legado.app.data.repository.toJsonArray
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.FontFolderState
import io.legado.app.ui.widget.components.FontSelectSheet
import io.legado.app.ui.widget.components.SectionTitle
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
import io.legado.app.ui.widget.components.text.AppText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import io.legado.app.utils.toastOnUi
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
    var textColor by remember(show, rule) {
        mutableIntStateOf(
            initial.textColor ?: 0xFF63C37D.toInt()
        )
    }
    var hasTextColor by remember(show, rule) { mutableStateOf(initial.textColor != null) }
    // 夜间色：null 表示自动派生（跟随日间色明度反相）
    var textColorNight by remember(show, rule) { mutableStateOf(initial.textColorNight) }
    var bgColor by remember(show, rule) { mutableIntStateOf(initial.bgColor ?: 0x20FFEB3B) }
    var hasBgColor by remember(show, rule) { mutableStateOf(initial.bgColor != null) }
    var bgColorNight by remember(show, rule) { mutableStateOf(initial.bgColorNight) }
    var hasUnderline by remember(show, rule) { mutableStateOf(initial.underlineMode > 0) }
    var underlineMode by remember(
        show,
        rule
    ) { mutableIntStateOf(if (initial.underlineMode > 0) initial.underlineMode else 1) }
    var underlineColor by remember(show, rule) {
        mutableIntStateOf(
            initial.underlineColor ?: 0xFF63C37D.toInt()
        )
    }
    var hasUnderlineColor by remember(show, rule) { mutableStateOf(initial.underlineColor != null) }
    var underlineColorNight by remember(show, rule) { mutableStateOf(initial.underlineColorNight) }
    var underlineWidth by remember(show, rule) { mutableFloatStateOf(initial.underlineWidth) }
    var underlineOffset by remember(show, rule) { mutableFloatStateOf(initial.underlineOffset) }
    var underlineSvgPath by remember(
        show,
        rule
    ) { mutableStateOf(initial.underlineSvgPath.orEmpty()) }
    var underlineBelowText by remember(show, rule) { mutableStateOf(initial.underlineBelowText) }
    var underlineRoundCap by remember(show, rule) { mutableStateOf(initial.underlineRoundCap) }
    var underlineFeather by remember(show, rule) { mutableFloatStateOf(initial.underlineFeather) }
    var underlineDashLen by remember(show, rule) { mutableFloatStateOf(initial.underlineDashLen) }
    var underlineDashGap by remember(show, rule) { mutableFloatStateOf(initial.underlineDashGap) }
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
                            textColor = if (hasTextColor) textColor else null,
                            textColorNight = if (hasTextColor) textColorNight else null,
                            bgColor = if (hasBgColor) bgColor else null,
                            bgColorNight = if (hasBgColor) bgColorNight else null,
                            underlineMode = if (hasUnderline) underlineMode else 0,
                            underlineColor = if (hasUnderlineColor && hasUnderline) underlineColor else null,
                            underlineColorNight = if (hasUnderlineColor && hasUnderline) underlineColorNight else null,
                            underlineWidth = underlineWidth,
                            underlineOffset = underlineOffset,
                            underlineSvgPath = underlineSvgPath.ifBlank { null },
                            underlineRoundCap = underlineRoundCap,
                            underlineFeather = underlineFeather,
                            underlineBelowText = underlineBelowText,
                            underlineDashLen = underlineDashLen,
                            underlineDashGap = underlineDashGap,
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
                        )
                    )
                },
                icon = Icons.Default.Done,
                contentDescription = stringResource(R.string.save),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
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
            TinySwitchSettingItem(
                title = stringResource(R.string.text_color),
                checked = hasTextColor,
                onCheckedChange = { hasTextColor = it },
            )
            AnimatedVisibility(visible = hasTextColor) {
                TinyClearColorModeSettingItem(
                    title = stringResource(R.string.select_color),
                    dayColor = textColor,
                    nightColor = textColorNight ?: ColorUtils.flipLightness(textColor),
                    onClickColor = { isNight ->
                        if (isNight) showTextColorNightPicker = true
                        else showTextColorPicker = true
                    },
                    onClearColor = { isNight ->
                        if (isNight) textColorNight = null
                    },
                )
            }

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

            // Underline
            TinySwitchSettingItem(
                title = stringResource(R.string.underline_style),
                checked = hasUnderline,
                onCheckedChange = { hasUnderline = it },
            )
            AnimatedVisibility(visible = hasUnderline) {
                Column {
                    val underlineEntries = arrayOf(
                        stringResource(R.string.underline_solid),
                        stringResource(R.string.underline_dashed),
                        stringResource(R.string.underline_wave),
                        stringResource(R.string.underline_title_bar),
                        stringResource(R.string.underline_svg),
                    )
                    val underlineValues = arrayOf("1", "2", "3", "4", "5")
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.underline_style),
                        selectedValue = underlineMode.toString(),
                        displayEntries = underlineEntries,
                        entryValues = underlineValues,
                        onValueChange = { underlineMode = it.toIntOrNull() ?: 1 },
                    )

                    AnimatedVisibility(visible = underlineMode == 5) {
                        AppTextField(
                            value = underlineSvgPath,
                            onValueChange = { underlineSvgPath = it },
                            label = stringResource(R.string.svg_path),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    TinySwitchSettingItem(
                        title = stringResource(R.string.underline_color),
                        checked = hasUnderlineColor,
                        onCheckedChange = { hasUnderlineColor = it },
                    )
                    AnimatedVisibility(visible = hasUnderlineColor) {
                        TinyClearColorModeSettingItem(
                            title = stringResource(R.string.select_color),
                            dayColor = underlineColor,
                            nightColor = underlineColorNight
                                ?: ColorUtils.flipLightness(underlineColor),
                            onClickColor = { isNight ->
                                if (isNight) showUnderlineColorNightPicker = true
                                else showUnderlineColorPicker = true
                            },
                            onClearColor = { isNight ->
                                if (isNight) underlineColorNight = null
                            },
                        )
                    }

                    TinySliderSettingItem(
                        title = stringResource(R.string.underline_width),
                        value = underlineWidth,
                        valueRange = 0.1f..20f,
                        description = String.format("%.1f dp", underlineWidth),
                        onValueChange = { underlineWidth = (it * 10).toInt() / 10f },
                        onReset = { underlineWidth = 1f },
                    )

                    TinySliderSettingItem(
                        title = stringResource(R.string.underline_offset),
                        value = underlineOffset,
                        valueRange = -20f..10f,
                        description = String.format("%+.1f dp", underlineOffset),
                        onValueChange = { underlineOffset = (it * 10).toInt() / 10f },
                        onReset = { underlineOffset = 2f },
                    )

                    TinySwitchSettingItem(
                        title = stringResource(R.string.underline_below_text),
                        checked = underlineBelowText,
                        onCheckedChange = { underlineBelowText = it },
                    )

                    TinySwitchSettingItem(
                        title = stringResource(R.string.underline_round_cap),
                        checked = underlineRoundCap,
                        onCheckedChange = { underlineRoundCap = it },
                    )

                    TinySliderSettingItem(
                        title = stringResource(R.string.underline_feather),
                        value = underlineFeather,
                        valueRange = 0f..5f,
                        description = String.format("%.1f dp", underlineFeather),
                        onValueChange = { underlineFeather = (it * 10).toInt() / 10f },
                    )

                    AnimatedVisibility(visible = underlineMode == 2) {
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
                }
            }

            // Background color
            TinySwitchSettingItem(
                title = stringResource(R.string.bg_color),
                checked = hasBgColor,
                onCheckedChange = { hasBgColor = it },
            )
            AnimatedVisibility(visible = hasBgColor) {
                TinyClearColorModeSettingItem(
                    title = stringResource(R.string.select_color),
                    dayColor = bgColor,
                    nightColor = bgColorNight ?: ColorUtils.flipLightness(bgColor),
                    onClickColor = { isNight ->
                        if (isNight) showBgColorNightPicker = true
                        else showBgColorPicker = true
                    },
                    onClearColor = { isNight ->
                        if (isNight) bgColorNight = null
                    },
                )
            }

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
                                        val range = -16f..24f
                                        val currentVal = when (activeInset) {
                                            0 -> bgPaddingStart; 1 -> bgPaddingEnd
                                            2 -> bgPaddingTop; else -> bgPaddingBottom
                                        }
                                        Slider(
                                            value = currentVal,
                                            onValueChange = { v ->
                                                val rounded = v.toInt().toFloat()
                                                when (activeInset) {
                                                    0 -> bgPaddingStart = rounded
                                                    1 -> bgPaddingEnd = rounded
                                                    2 -> bgPaddingTop = rounded
                                                    3 -> bgPaddingBottom = rounded
                                                }
                                            },
                                            valueRange = range,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        )
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
                                        Slider(
                                            value = currentVal,
                                            onValueChange = { v ->
                                                val rounded = v.toInt().toFloat()
                                                when (activeMargin) {
                                                    0 -> bgMarginStart = rounded
                                                    1 -> bgMarginEnd = rounded
                                                    2 -> bgMarginTop = rounded
                                                    3 -> bgMarginBottom = rounded
                                                }
                                            },
                                            valueRange = -8f..32f,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        )
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

            // === Section 5: Preview ===
            SectionTitle(stringResource(R.string.preview_effect))

            AppTextField(
                value = sampleText,
                onValueChange = { sampleText = it },
                label = stringResource(R.string.sample_text),
                modifier = Modifier.fillMaxWidth(),
            )

            HighlightRulePreview(
                label = stringResource(R.string.day),
                sampleText = sampleText,
                pattern = pattern,
                textColor = if (hasTextColor) textColor else null,
                bgColor = if (hasBgColor) bgColor else null,
                bgImage = if (hasBgImage) bgImage else "",
                bgImageFit = bgImageFit,
                bgImageScale = bgImageScale,
                underlineMode = if (hasUnderline) underlineMode else 0,
                underlineColor = if (hasUnderlineColor && hasUnderline) underlineColor else null,
                underlineWidth = underlineWidth,
                underlineOffset = underlineOffset,
                pageBgColor = runCatching {
                    android.graphics.Color.parseColor(ReadBookConfig.durConfig.bgStr)
                }.getOrDefault(0xFFEEEEEE.toInt()),
                pageTextColor = ReadBookConfig.textColor,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            // Night preview — hidden when bgImage is active
            if (!hasBgImage || bgImage.isBlank()) {
                val nightTextColor = if (hasTextColor)
                    (textColorNight ?: ColorUtils.flipLightness(textColor)) else null
                val nightBgColor = if (hasBgColor)
                    (bgColorNight ?: ColorUtils.flipLightness(bgColor)) else null
                val nightUnderlineColor = if (hasUnderlineColor && hasUnderline)
                    (underlineColorNight ?: ColorUtils.flipLightness(underlineColor)) else null
                HighlightRulePreview(
                    label = stringResource(R.string.night),
                    sampleText = sampleText,
                    pattern = pattern,
                    textColor = nightTextColor,
                    bgColor = nightBgColor,
                    bgImage = "",
                    bgImageFit = bgImageFit,
                    bgImageScale = bgImageScale,
                    underlineMode = if (hasUnderline) underlineMode else 0,
                    underlineColor = nightUnderlineColor,
                    underlineWidth = underlineWidth,
                    underlineOffset = underlineOffset,
                    pageBgColor = runCatching {
                        android.graphics.Color.parseColor(ReadBookConfig.durConfig.bgStrNight)
                    }.getOrDefault(0xFF000000.toInt()),
                    pageTextColor = ReadBookConfig.textColorNight,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }

    // Color pickers
    ColorPickerSheet(
        show = showTextColorPicker,
        initialColor = textColor,
        onDismissRequest = { showTextColorPicker = false },
        onColorSelected = { color ->
            textColor = color
            showTextColorPicker = false
        },
    )
    ColorPickerSheet(
        show = showBgColorPicker,
        initialColor = bgColor,
        onDismissRequest = { showBgColorPicker = false },
        onColorSelected = { color ->
            bgColor = color
            showBgColorPicker = false
        },
    )
    ColorPickerSheet(
        show = showUnderlineColorPicker,
        initialColor = underlineColor,
        onDismissRequest = { showUnderlineColorPicker = false },
        onColorSelected = { color ->
            underlineColor = color
            showUnderlineColorPicker = false
        },
    )
    // Night color pickers
    ColorPickerSheet(
        show = showTextColorNightPicker,
        initialColor = textColorNight ?: ColorUtils.flipLightness(textColor),
        onDismissRequest = { showTextColorNightPicker = false },
        onColorSelected = { color ->
            textColorNight = color
            showTextColorNightPicker = false
        },
    )
    ColorPickerSheet(
        show = showBgColorNightPicker,
        initialColor = bgColorNight ?: ColorUtils.flipLightness(bgColor),
        onDismissRequest = { showBgColorNightPicker = false },
        onColorSelected = { color ->
            bgColorNight = color
            showBgColorNightPicker = false
        },
    )
    ColorPickerSheet(
        show = showUnderlineColorNightPicker,
        initialColor = underlineColorNight ?: ColorUtils.flipLightness(underlineColor),
        onDismissRequest = { showUnderlineColorNightPicker = false },
        onColorSelected = { color ->
            underlineColorNight = color
            showUnderlineColorNightPicker = false
        },
    )

    // Nine-patch editor
    NinePatchEditorDialog(
        show = showNinePatchEditor,
        imagePath = bgImage,
        // np* 存的是「角块占比」，编辑器内部用「绝对线位置(0~1)」，此处做转换
        initialLeft = npLeft,
        initialRight = 1f - npRight,
        initialTop = npTop,
        initialBottom = 1f - npBottom,
        onDismissRequest = { showNinePatchEditor = false },
        onSave = { left, right, top, bottom ->
            npLeft = left
            npRight = 1f - right
            npTop = top
            npBottom = 1f - bottom
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

private const val PREVIEW_BASE_FONT_SIZE = 16

@Composable
private fun HighlightRulePreview(
    label: String,
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
    pageBgColor: Int,
    pageTextColor: Int,
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

    val annotated = remember(
        sampleText, matchRanges, resolvedTextColor, bgColor, fontWeight, isItalic, fontSizeOffset
    ) {
        buildAnnotatedString {
            append(sampleText)
            matchRanges.forEach { range ->
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
                            (PREVIEW_BASE_FONT_SIZE + fontSizeOffset).sp
                        } else {
                            androidx.compose.ui.unit.TextUnit.Unspecified
                        },
                    ),
                    range.first,
                    (range.last + 1).coerceAtMost(sampleText.length),
                )
            }
        }
    }

    val labelColor = if (ColorUtils.isColorLight(pageBgColor)) {
        Color(0x99000000)
    } else {
        Color(0x99FFFFFF)
    }
    NormalCard(
        modifier = modifier,
        cornerRadius = 12.dp,
        containerColor = Color(pageBgColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AppText(
                text = label,
                style = LegadoTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (pattern.isNotBlank() && matchRanges.isEmpty()) {
                AppText(
                    text = stringResource(R.string.highlight_preview_no_match),
                    style = LegadoTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            // 预先测量文本以获取实际高度（使用卡片内容宽度估计值）
            val density = LocalDensity.current
            val previewConstraintWidth = with(density) {
                // 卡片内内容宽度 ≈ 屏幕宽 - sheet水平padding*2(16*2) - 卡片内padding*2(16*2)
                (LocalConfiguration.current.screenWidthDp.dp - 64.dp).roundToPx()
            }
            val previewTextResult = textMeasurer.measure(
                text = annotated,
                style = TextStyle(
                    fontSize = PREVIEW_BASE_FONT_SIZE.sp,
                    color = defaultTextColor,
                ),
                maxLines = 5,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = previewConstraintWidth),
            )
            val canvasHeightDp = with(density) {
                previewTextResult.size.height.toDp()
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp)
            ) {
                val textResult = textMeasurer.measure(
                    text = annotated,
                    style = TextStyle(
                        fontSize = PREVIEW_BASE_FONT_SIZE.sp,
                        color = defaultTextColor,
                    ),
                    maxLines = 5,
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = size.width.toInt()
                    ),
                )

                // 在匹配区域画背景图
                if (bgBitmap != null && matchRanges.isNotEmpty()) {
                    val density = this.density
                    matchRanges.forEach { range ->
                        val start = range.first
                        val endExclusive = (range.last + 1).coerceAtMost(sampleText.length)
                        if (start >= endExclusive) return@forEach
                        var offset = start
                        while (offset < endExclusive) {
                            val line = textResult.getLineForOffset(offset)
                            val lineEnd = textResult.getLineEnd(line, visibleEnd = true)
                            val segEnd = minOf(endExclusive, lineEnd)
                            if (segEnd <= offset) break
                            val left = textResult.getHorizontalPosition(offset, usePrimaryDirection = true)
                            val right = textResult.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                            val top = textResult.getLineTop(line)
                            val bottom = textResult.getLineBottom(line)
                            val rectL = minOf(left, right)
                            val rectR = maxOf(left, right)
                            val rectT = top
                            val rectB = bottom
                            if (bgImageFit == 3 && bgRawBitmap != null) {
                                // 让文字落在九宫格中段拉伸区内，四角自然落在文字外部；padding 再向外扩
                                val textW = rectR - rectL
                                val textH = rectB - rectT
                                val bw = bgRawBitmap.width.toFloat()
                                val bh = bgRawBitmap.height.toFloat()
                                val s = if (bh > 0f) textH / bh else 1f
                                val maxCornerV = textH * 0.5f
                                val maxCornerH = (rectR - rectL) * 0.5f
                                val cornerL = (npLeft * bw * s).coerceAtMost(maxCornerH)
                                val cornerR = (npRight * bw * s).coerceAtMost(maxCornerH)
                                val cornerT = (npTop * bh * s).coerceAtMost(maxCornerV)
                                val cornerB = (npBottom * bh * s).coerceAtMost(maxCornerV)
                                val drawLeft = rectL - cornerL - bgPadStart * density
                                val drawTop = rectT - cornerT - bgPadTop * density
                                val drawRight = rectR + cornerR + bgPadEnd * density
                                val drawBottom = rectB + cornerB + bgPadBottom * density
                                io.legado.app.help.highlight.NinePatchDrawHelper.draw(
                                    drawContext.canvas.nativeCanvas,
                                    bgRawBitmap,
                                    drawLeft,
                                    drawTop,
                                    drawRight,
                                    drawBottom,
                                    ninePatchPaint,
                                    npLeft,
                                    1f - npRight,
                                    npTop,
                                    1f - npBottom,
                                )
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
                            offset = segEnd
                        }
                    }
                }

                // 下划线绘制块（可在文字上层或下层）
                val drawUnderlinesBlock: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                    if (underlineMode > 0) {
                        val strokeWidth = underlineWidth.dp.toPx()
                        matchRanges.forEach { range ->
                            val start = range.first
                            val endExclusive = (range.last + 1).coerceAtMost(sampleText.length)
                            if (start >= endExclusive) return@forEach
                            var offset = start
                            while (offset < endExclusive) {
                                val line = textResult.getLineForOffset(offset)
                                val lineEnd = textResult.getLineEnd(line, visibleEnd = true)
                                val segEnd = minOf(endExclusive, lineEnd)
                                if (segEnd <= offset) break
                                val left = textResult.getHorizontalPosition(offset, usePrimaryDirection = true)
                                val right = textResult.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                                val y = textResult.getLineBottom(line) + underlineOffset.dp.toPx()
                                drawUnderlineSegment(
                                    mode = underlineMode,
                                    color = resolvedUnderlineColor,
                                    strokeWidth = strokeWidth,
                                    startX = minOf(left, right),
                                    endX = maxOf(left, right),
                                    y = y,
                                    roundCap = underlineRoundCap,
                                    feather = underlineFeather,
                                    dashLen = underlineDashLen,
                                    dashGap = underlineDashGap,
                                )
                                offset = segEnd
                            }
                        }
                    }
                }

                if (underlineBelowText) drawUnderlinesBlock()
                drawText(textResult)
                if (!underlineBelowText) drawUnderlinesBlock()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnderlineSegment(
    mode: Int,
    color: Color,
    strokeWidth: Float,
    startX: Float,
    endX: Float,
    y: Float,
    roundCap: Boolean = false,
    feather: Float = 0f,
    dashLen: Float = 8f,
    dashGap: Float = 5f,
) {
    val cap = if (roundCap || feather > 0f) StrokeCap.Round else StrokeCap.Butt
    if (feather > 0f) {
        val passes = (feather * 3f).toInt().coerceIn(6, 24)
        val baseAlpha = color.alpha
        val featherPx = feather.dp.toPx()
        val sigma = 0.55f
        // 端部渐隐长度：至少覆盖羽化扩散半径与线宽
        val featherLen = maxOf(feather.dp.toPx() * 1.5f, strokeWidth)
        val segLen = endX - startX
        val edgePos = if (segLen > 0f) (featherLen / segLen).coerceIn(0f, 0.5f) else 0.5f
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val gaussian = kotlin.math.exp(-(d * d) / (2f * sigma * sigma)).toFloat()
            val alpha = baseAlpha * gaussian
            if (alpha <= 0.001f) continue
            val passColor = color.copy(alpha = alpha)
            val passWidth = strokeWidth + d * featherPx * 2f
            // 端点水平渐隐：两端 alpha 渐变为 0
            val brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    edgePos to passColor,
                    1f - edgePos to passColor,
                    1f to Color.Transparent,
                ),
                start = Offset(startX, 0f),
                end = Offset(endX, 0f),
            )
            drawUnderlineShape(mode, passColor, passWidth, startX, endX, y, cap, brush, dashLen, dashGap)
        }
    } else {
        drawUnderlineShape(mode, color, strokeWidth, startX, endX, y, cap, dashLen = dashLen, dashGap = dashGap)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnderlineShape(
    mode: Int,
    color: Color,
    strokeWidth: Float,
    startX: Float,
    endX: Float,
    y: Float,
    cap: StrokeCap = StrokeCap.Butt,
    brush: Brush? = null,
    dashLen: Float = 8f,
    dashGap: Float = 5f,
) {
    // 圆头向外延伸半个宽度，收缩补偿以保持总长不变
    val capInset = if (cap == StrokeCap.Round) strokeWidth / 2f else 0f
    val sx = startX + capInset
    val ex = endX - capInset
    if (sx >= ex) return
    when (mode) {
        1 -> drawLine(
            brush = brush ?: Brush.linearGradient(listOf(color, color)),
            start = Offset(sx, y),
            end = Offset(ex, y),
            strokeWidth = strokeWidth,
            cap = cap,
        )

        2 -> {
            val dashLength = dashLen.dp.toPx()
            val gapLength = dashGap.dp.toPx()
            if (dashLength + gapLength <= 0f) {
                drawLine(
                    brush = brush ?: Brush.linearGradient(listOf(color, color)),
                    start = Offset(sx, y),
                    end = Offset(ex, y),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
                return
            }
            var x = sx
            while (x < ex) {
                val segEndX = minOf(x + dashLength, ex)
                drawLine(
                    brush = brush ?: Brush.linearGradient(listOf(color, color)),
                    start = Offset(x, y),
                    end = Offset(segEndX, y),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
                x += dashLength + gapLength
            }
        }

        3 -> {
            // 波浪：控制点需 2 倍振幅，二次贝塞尔在中点的实际高度是 (基线Y + 控制点Y)/2
            val amplitude = 2.5.dp.toPx()
            val halfPeriod = 8.dp.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(sx, y)
                var x = sx
                var up = true
                while (x < ex) {
                    val nextX = minOf(x + halfPeriod, ex)
                    val midX = (x + nextX) / 2f
                    val controlY = if (up) y - 2f * amplitude else y + 2f * amplitude
                    quadraticTo(midX, controlY, nextX, y)
                    x = nextX
                    up = !up
                }
            }
            drawPath(
                path = path,
                brush = brush ?: Brush.linearGradient(listOf(color, color)),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        4 -> {
            val gap = 2.dp.toPx()
            drawLine(
                brush = brush ?: Brush.linearGradient(listOf(color, color)),
                start = Offset(sx, y - gap),
                end = Offset(ex, y - gap),
                strokeWidth = strokeWidth,
                cap = cap,
            )
            drawLine(
                brush = brush ?: Brush.linearGradient(listOf(color, color)),
                start = Offset(sx, y + gap),
                end = Offset(ex, y + gap),
                strokeWidth = strokeWidth,
                cap = cap,
            )
        }
    }
}

@Composable
private fun NinePatchEditorDialog(
    show: Boolean,
    imagePath: String,
    initialLeft: Float,
    initialRight: Float,
    initialTop: Float,
    initialBottom: Float,
    onDismissRequest: () -> Unit,
    onSave: (left: Float, right: Float, top: Float, bottom: Float) -> Unit,
) {
    var left by remember(show, imagePath) { mutableFloatStateOf(initialLeft) }
    var right by remember(show, imagePath) { mutableFloatStateOf(initialRight) }
    var top by remember(show, imagePath) { mutableFloatStateOf(initialTop) }
    var bottom by remember(show, imagePath) { mutableFloatStateOf(initialBottom) }

    val bitmap = remember(imagePath) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) BitmapFactory.decodeFile(imagePath) else null
        }.getOrNull()
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.nine_patch_editor),
        endAction = {
            MediumTonalButton(
                onClick = { onSave(left, right, top, bottom) },
                icon = Icons.Default.Done,
                contentDescription = stringResource(R.string.save),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 切分卡片：图片保持原始宽高比
            if (bitmap != null) {
                val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                var imageRect by remember { mutableStateOf(Rect.Zero) }
                val splitLineColor = MaterialTheme.colorScheme.primary
                NormalCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    cornerRadius = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(bmpAspect),
                        ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // 参照 R 项目：按下时锁定最近的线，拖动期间只更新锁定目标
                                var dragTarget by mutableIntStateOf(-1)
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val ir = imageRect
                                        if (ir.width <= 0f || ir.height <= 0f) { dragTarget = -1; return@detectDragGestures }
                                        val relX = (offset.x - ir.left) / ir.width
                                        val relY = (offset.y - ir.top) / ir.height
                                        // 四条线的绝对位置（left/right 都是从左数，top/bottom 都是从上数）
                                        val dL = kotlin.math.abs(relX - left)
                                        val dR = kotlin.math.abs(relX - right)
                                        val dT = kotlin.math.abs(relY - top)
                                        val dB = kotlin.math.abs(relY - bottom)
                                        // 竖线取最近的一条、横线取最近的一条
                                        val bestV = if (dL <= dR) Pair(0, dL) else Pair(1, dR)
                                        val bestH = if (dT <= dB) Pair(2, dT) else Pair(3, dB)
                                        dragTarget = if (bestV.second <= bestH.second) {
                                            if (bestV.second <= 0.15f) bestV.first else -1
                                        } else {
                                            if (bestH.second <= 0.15f) bestH.first else -1
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (dragTarget == -1) return@detectDragGestures
                                        val ir = imageRect
                                        if (ir.width <= 0f || ir.height <= 0f) return@detectDragGestures
                                        val relX = ((change.position.x - ir.left) / ir.width).coerceIn(0.02f, 0.98f)
                                        val relY = ((change.position.y - ir.top) / ir.height).coerceIn(0.02f, 0.98f)
                                        when (dragTarget) {
                                            0 -> left = relX
                                            1 -> right = relX
                                            2 -> top = relY
                                            3 -> bottom = relY
                                        }
                                    },
                                    onDragEnd = { dragTarget = -1 },
                                    onDragCancel = { dragTarget = -1 },
                                )
                            }
                    ) {
                        // 图片填满 Canvas（已用 aspectRatio 保证比例）
                        val canvasW = size.width
                        val canvasH = size.height
                        imageRect = Rect(0f, 0f, canvasW, canvasH)

                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                            dstSize = androidx.compose.ui.unit.IntSize(canvasW.toInt(), canvasH.toInt()),
                        )

                        val lineColor = splitLineColor
                        val lineWidth = 2.dp.toPx()
                        // left/right/top/bottom 都是从左/上数的绝对位置(0~1)
                        val lx = canvasW * left
                        val rx = canvasW * right
                        val ty = canvasH * top
                        val by2 = canvasH * bottom
                        drawLine(lineColor, Offset(lx, 0f), Offset(lx, canvasH), lineWidth)
                        drawLine(lineColor, Offset(rx, 0f), Offset(rx, canvasH), lineWidth)
                        drawLine(lineColor, Offset(0f, ty), Offset(canvasW, ty), lineWidth)
                        drawLine(lineColor, Offset(0f, by2), Offset(canvasW, by2), lineWidth)
                        // 中间矩形（可拉伸区）描边
                        val minX = minOf(lx, rx); val maxX = maxOf(lx, rx)
                        val minY = minOf(ty, by2); val maxY = maxOf(ty, by2)
                        drawRect(
                            color = lineColor.copy(alpha = 0.3f),
                            topLeft = Offset(minX, minY),
                            size = androidx.compose.ui.geometry.Size(maxX - minX, maxY - minY),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                }
                }
                Text(
                    text = "拖动线条调整切分位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                NineSlicePreview(
                    imagePath = imagePath,
                    npLeft = left,
                    npRight = right,
                    npTop = top,
                    npBottom = bottom,
                )
            }
        }
    }
}

/**
 * 九宫格预览：背景图铺占大部分区域，中央虚线框代表文字，直观表达"背景包住文字"
 */
@Composable
private fun NineSlicePreview(
    imagePath: String,
    npLeft: Float,
    npRight: Float,
    npTop: Float,
    npBottom: Float,
) {
    val bitmap = remember(imagePath) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) BitmapFactory.decodeFile(imagePath) else null
        }.getOrNull()
    }
    if (bitmap == null) return

    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        cornerRadius = 12.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val canvasW = size.width
        val canvasH = size.height
        // 背景图铺占 canvas 大部分（四周各留 8%），直观表达"背景包住文字"
        val bgLeft = canvasW * 0.08f
        val bgRight = canvasW * 0.92f
        val bgTop = canvasH * 0.08f
        val bgBottom = canvasH * 0.92f

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        io.legado.app.help.highlight.NinePatchDrawHelper.draw(
            drawContext.canvas.nativeCanvas, bitmap,
            bgLeft, bgTop, bgRight, bgBottom,
            paint, npLeft, 1f - npRight, npTop, 1f - npBottom,
        )
        // 文字行虚线：根据切分线位置，正好落在九宫格中段拉伸区内
        val textLeft = bgLeft + (bgRight - bgLeft) * npLeft
        val textRight = bgRight - (bgRight - bgLeft) * npRight
        val textTop = bgTop + (bgBottom - bgTop) * npTop
        val textBottom = bgBottom - (bgBottom - bgTop) * npBottom
        drawRect(
            color = Color(0x66000000),
            topLeft = Offset(textLeft, textTop),
            size = androidx.compose.ui.geometry.Size(textRight - textLeft, textBottom - textTop),
            style = Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
        )
    }
    }
}
