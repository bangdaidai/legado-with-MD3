package io.legado.app.ui.book.read.sheet

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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

    // File picker for background images (uses OpenDocument to avoid MediaStore transcoding)
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
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
                            bgImage = if (hasBgImage) bgImage.ifBlank { null } else null,
                            bgImageFit = bgImageFit,
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
                supportingText = patternError?.let {
                    { AppText(it, color = MaterialTheme.colorScheme.error) }
                },
            )

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
                        valueRange = 0.1f..10f,
                        description = String.format("%.1f dp", underlineWidth),
                        onValueChange = { underlineWidth = (it * 10).toInt() / 10f },
                    )

                    TinySliderSettingItem(
                        title = stringResource(R.string.underline_offset),
                        value = underlineOffset,
                        valueRange = 0f..20f,
                        description = String.format("%.1f dp", underlineOffset),
                        onValueChange = { underlineOffset = (it * 10).toInt() / 10f },
                    )

                    AnimatedVisibility(visible = underlineMode == 5) {
                        AppTextField(
                            value = underlineSvgPath,
                            onValueChange = { underlineSvgPath = it },
                            label = stringResource(R.string.svg_path),
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                        imagePicker.launch(arrayOf("image/*"))
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
                                Column {
                                    TinySliderSettingItem(title = "左", value = bgPaddingStart, valueRange = -8f..24f, description = "${bgPaddingStart.toInt()} dp", onValueChange = { bgPaddingStart = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "右", value = bgPaddingEnd, valueRange = -8f..24f, description = "${bgPaddingEnd.toInt()} dp", onValueChange = { bgPaddingEnd = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "上", value = bgPaddingTop, valueRange = -8f..24f, description = "${bgPaddingTop.toInt()} dp", onValueChange = { bgPaddingTop = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "下", value = bgPaddingBottom, valueRange = -8f..24f, description = "${bgPaddingBottom.toInt()} dp", onValueChange = { bgPaddingBottom = it.toInt().toFloat() })
                                }
                            }
                            TinyClickableSettingItem(
                                title = "外边距",
                                description = String.format("左%.0f 右%.0f 上%.0f 下%.0f", bgMarginStart, bgMarginEnd, bgMarginTop, bgMarginBottom),
                                onClick = { showMarginEditor = !showMarginEditor },
                            )
                            AnimatedVisibility(visible = showMarginEditor) {
                                Column {
                                    TinySliderSettingItem(title = "左", value = bgMarginStart, valueRange = 0f..32f, description = "${bgMarginStart.toInt()} dp", onValueChange = { bgMarginStart = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "右", value = bgMarginEnd, valueRange = 0f..32f, description = "${bgMarginEnd.toInt()} dp", onValueChange = { bgMarginEnd = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "上", value = bgMarginTop, valueRange = 0f..32f, description = "${bgMarginTop.toInt()} dp", onValueChange = { bgMarginTop = it.toInt().toFloat() })
                                    TinySliderSettingItem(title = "下", value = bgMarginBottom, valueRange = 0f..32f, description = "${bgMarginBottom.toInt()} dp", onValueChange = { bgMarginBottom = it.toInt().toFloat() })
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

            // === Section 4: Font ===
            SectionTitle("字体替换")
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
        initialLeft = npLeft,
        initialRight = npRight,
        initialTop = npTop,
        initialBottom = npBottom,
        onDismissRequest = { showNinePatchEditor = false },
        onSave = { left, right, top, bottom ->
            npLeft = left
            npRight = right
            npTop = top
            npBottom = bottom
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

    val annotated = remember(sampleText, matchRanges, resolvedTextColor, bgColor) {
        buildAnnotatedString {
            append(sampleText)
            matchRanges.forEach { range ->
                addStyle(
                    SpanStyle(
                        color = resolvedTextColor,
                        background = if (bgColor != null && bgBitmap == null) Color(bgColor) else Color.Unspecified,
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
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                val textResult = textMeasurer.measure(
                    text = annotated,
                    style = TextStyle(
                        fontSize = (16 + fontSizeOffset).sp,
                        color = defaultTextColor,
                    ),
                    maxLines = 3,
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
                                // 九宫格绘制：padding 向外扩展
                                val drawLeft = rectL - bgPadStart * density
                                val drawTop = rectT - bgPadTop * density
                                val drawRight = rectR + bgPadEnd * density
                                val drawBottom = rectB + bgPadBottom * density
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

                drawText(textResult)

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
                            val y = textResult.getLineBottom(line) - underlineOffset.dp.toPx()
                            drawUnderlineSegment(
                                mode = underlineMode,
                                color = resolvedUnderlineColor,
                                strokeWidth = strokeWidth,
                                startX = minOf(left, right),
                                endX = maxOf(left, right),
                                y = y,
                            )
                            offset = segEnd
                        }
                    }
                }
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
) {
    when (mode) {
        1 -> drawLine(
            color = color,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = strokeWidth,
        )

        2 -> {
            val dashLength = 8.dp.toPx()
            val gapLength = 4.dp.toPx()
            var x = startX
            while (x < endX) {
                val segEndX = minOf(x + dashLength, endX)
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(segEndX, y),
                    strokeWidth = strokeWidth,
                )
                x += dashLength + gapLength
            }
        }

        3 -> {
            // 波浪：控制点需 2 倍振幅，二次贝塞尔在中点的实际高度是 (基线Y + 控制点Y)/2
            val amplitude = 2.5.dp.toPx()
            val halfPeriod = 8.dp.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(startX, y)
                var x = startX
                var up = true
                while (x < endX) {
                    val nextX = minOf(x + halfPeriod, endX)
                    val midX = (x + nextX) / 2f
                    val controlY = if (up) y - 2f * amplitude else y + 2f * amplitude
                    quadraticTo(midX, controlY, nextX, y)
                    x = nextX
                    up = !up
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        4 -> {
            val gap = 2.dp.toPx()
            drawLine(
                color = color,
                start = Offset(startX, y - gap),
                end = Offset(endX, y - gap),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = color,
                start = Offset(startX, y + gap),
                end = Offset(endX, y + gap),
                strokeWidth = strokeWidth,
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(bmpAspect)
                        .align(Alignment.CenterHorizontally)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val pos = change.position
                                    val ir = imageRect
                                    if (ir.width <= 0f || ir.height <= 0f) return@detectDragGestures
                                    val relX = (pos.x - ir.left) / ir.width
                                    val relY = (pos.y - ir.top) / ir.height
                                    val lx = left
                                    val rx = 1f - right
                                    val ty = top
                                    val by2 = 1f - bottom
                                    val distL = kotlin.math.abs(relX - lx)
                                    val distR = kotlin.math.abs(relX - rx)
                                    val distT = kotlin.math.abs(relY - ty)
                                    val distB = kotlin.math.abs(relY - by2)
                                    val minDist = minOf(distL, distR, distT, distB)
                                    if (minDist > 0.15f) return@detectDragGestures
                                    when (minDist) {
                                        distL -> left = relX.coerceIn(0.01f, 0.99f)
                                        distR -> right = (1f - relX).coerceIn(0.01f, 0.99f)
                                        distT -> top = relY.coerceIn(0.01f, 0.99f)
                                        distB -> bottom = (1f - relY).coerceIn(0.01f, 0.99f)
                                    }
                                }
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
                        val lx = canvasW * left
                        drawLine(lineColor, Offset(lx, 0f), Offset(lx, canvasH), lineWidth)
                        val rx = canvasW * (1f - right)
                        drawLine(lineColor, Offset(rx, 0f), Offset(rx, canvasH), lineWidth)
                        val ty = canvasH * top
                        drawLine(lineColor, Offset(0f, ty), Offset(canvasW, ty), lineWidth)
                        val by = canvasH * (1f - bottom)
                        drawLine(lineColor, Offset(0f, by), Offset(canvasW, by), lineWidth)
                    }
                }
                Text(
                    text = "拖动线条调整切分位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
        // 文字行虚线：缩到 canvas 中央一小块，明显被背景图包住
        val textLeft = canvasW * 0.25f
        val textRight = canvasW * 0.75f
        val textTop = canvasH * 0.35f
        val textBottom = canvasH * 0.65f
        drawRect(
            color = Color(0x66000000),
            topLeft = Offset(textLeft, textTop),
            size = androidx.compose.ui.geometry.Size(textRight - textLeft, textBottom - textTop),
            style = Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
        )
    }
}
