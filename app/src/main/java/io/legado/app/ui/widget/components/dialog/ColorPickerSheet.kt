package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.util.ceil
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.utils.isHex
import top.yukonga.miuix.kmp.basic.ColorPalette
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    show: Boolean,
    initialColor: Int,
    onDismissRequest: () -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    var currentColor by remember { mutableStateOf(Color(initialColor)) }
    var hexInput by remember { mutableStateOf(initialColor.asHexColorString()) }
    var isHexInputError by remember { mutableStateOf(false) }
    // true = 原始色板网格，false = 色块面板（饱和度/明度大色块 + 色相 + 透明度滑块）
    var isPaletteMode by remember { mutableStateOf(true) }
    // 色板模式实测高度(px)，用于色块面板对齐总高
    var paletteHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(show, initialColor) {
        if (show) {
            currentColor = Color(initialColor)
            hexInput = initialColor.asHexColorString()
            isHexInputError = false
            isPaletteMode = true
        }
    }

    val parsedHexColor = parseHexColor(hexInput)

    fun applyColor(color: Color) {
        currentColor = color
        hexInput = color.toArgb().asHexColorString()
        isHexInputError = false
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.select_color),
        startAction = {
            MediumTonalButton(
                onClick = {
                    currentColor = Color.Transparent
                    hexInput = "#00000000"
                    isHexInputError = false
                },
                icon = Icons.Default.Restore,
                contentDescription = stringResource(R.string.reset),
            )
        },
        endAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 取色方式切换：与保存按钮同形式、紧邻保存按钮（对齐分享卡片「切换模板」按钮）
                MediumTonalButton(
                    onClick = { isPaletteMode = !isPaletteMode },
                    icon = Icons.Default.SwapHoriz,
                    contentDescription = if (isPaletteMode) {
                        stringResource(R.string.color_mixer)
                    } else {
                        stringResource(R.string.color_palette)
                    },
                )
                MediumTonalButton(
                    onClick = {
                        onColorSelected(currentColor.toArgb())
                        onDismissRequest()
                    },
                    enabled = parsedHexColor != null && !isHexInputError,
                    icon = Icons.Default.Save,
                    contentDescription = stringResource(R.string.action_save),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 记录色板模式实测高度，供色块面板模式对齐，切换时弹层不再跳高跳低
            when (isPaletteMode) {
                true -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { paletteHeightPx = it.height.toFloat() }
                ) {
                    PaletteMode(
                        currentColor = currentColor,
                        onColorChanged = ::applyColor,
                    )
                }
                false -> FieldMode(
                    currentColor = currentColor,
                    onColorChanged = ::applyColor,
                    targetHeightPx = paletteHeightPx,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentColor)
                        .border(
                            1.dp,
                            LegadoTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                AppTextField(
                    value = hexInput,
                    onValueChange = { value ->
                        hexInput = normalizeHexInput(value)
                        val parsedColor = parseHexColor(hexInput)
                        if (parsedColor != null) {
                            currentColor = Color(parsedColor)
                            isHexInputError = false
                        } else {
                            isHexInputError = hexInput.isNotBlank()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.color_value),
                    singleLine = true,
                    isError = isHexInputError,
                    backgroundColor = LegadoTheme.colorScheme.surfaceContainerLow,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    )
                )
            }
        }
    }
}

/** 原始色板模式：Miuix 色板网格，透明度由色板自身提供 */
@Composable
private fun PaletteMode(
    currentColor: Color,
    onColorChanged: (Color) -> Unit,
) {
    ColorPalette(
        color = currentColor,
        onColorChanged = onColorChanged,
        rows = 8,
        hueColumns = 12,
        modifier = Modifier.fillMaxWidth(),
        showPreview = false
    )
}

/**
 * 色块面板模式：一整块「饱和度 × 明度」取色区 + 色相滑块 + 透明度滑块。
 *
 * HSV 以本地状态为准，只在外部改色（十六进制输入/重置/重新打开）时才回同步；
 * 外部颜色是灰阶时保留当前色相，避免拖色相到灰色后滑块弹回红端。
 */
@Composable
private fun FieldMode(
    currentColor: Color,
    onColorChanged: (Color) -> Unit,
    targetHeightPx: Float,
) {
    val initialHsv = remember { colorToHsv(currentColor) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(currentColor.alpha) }
    var lastEmitted by remember { mutableStateOf(currentColor) }

    LaunchedEffect(currentColor) {
        if (currentColor == lastEmitted) return@LaunchedEffect
        val hsv = colorToHsv(currentColor)
        if (hsv[1] != 0f) hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        alpha = currentColor.alpha
        lastEmitted = currentColor
    }

    fun emit(h: Float, s: Float, v: Float, a: Float) {
        hue = h
        saturation = s
        brightness = v
        alpha = a
        val newColor = Color.hsv(h, s, v, a)
        lastEmitted = newColor
        onColorChanged(newColor)
    }

    // 总高对齐色板模式实测高度；未测得时给一个接近色板高度的兜底
    val density = LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (targetHeightPx > 0f) {
                    Modifier.height(with(density) { targetHeightPx.toDp() })
                } else {
                    Modifier.height(280.dp)
                }
            )
    ) {
        // 与色板网格同宽同边距：不额外加水平 padding
        SaturationValuePanel(
            hueColor = Color.hsv(hue, 1f, 1f),
            saturation = saturation,
            brightness = brightness,
            onSaturationBrightnessChanged = { s, v -> emit(hue, s, v, alpha) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) }
            ),
            position = hue / 360f,
            onPositionChanged = { pos -> emit(pos * 360f, saturation, brightness, alpha) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        val opaqueColor = Color.hsv(hue, saturation, brightness, 1f)
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    opaqueColor.copy(alpha = 0f),
                    opaqueColor,
                )
            ),
            checkerboard = true,
            position = alpha,
            onPositionChanged = { pos -> emit(hue, saturation, brightness, pos) },
        )
    }
}

/** 饱和度(横轴) × 明度(纵轴)取色面板：左上白、右上纯色相、底部黑 */
@Composable
private fun SaturationValuePanel(
    hueColor: Color,
    saturation: Float,
    brightness: Float,
    onSaturationBrightnessChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 与 Miuix ColorPalette 内置滑块同款：20dp 空心圆环取色点
    val thumbSizeDp = 20.dp
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    var panelHeightPx by remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                panelWidthPx = coords.size.width.toFloat()
                panelHeightPx = coords.size.height.toFloat()
            }
            .clip(shape)
            .background(Brush.horizontalGradient(colors = listOf(Color.White, hueColor)))
            .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black)))
            .pointerInput(hueColor) {
                detectTapGestures { tapOffset ->
                    onSaturationBrightnessChanged(
                        (tapOffset.x / size.width).coerceIn(0f, 1f),
                        1f - (tapOffset.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
            .pointerInput(hueColor) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onSaturationBrightnessChanged(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        1f - (change.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
    ) {
        if (panelWidthPx > 0f && panelHeightPx > 0f) {
            val thumbSizePx = with(density) { thumbSizeDp.toPx() }
            val thumbX = (saturation * panelWidthPx).coerceIn(
                thumbSizePx / 2,
                panelWidthPx - thumbSizePx / 2
            )
            val thumbY = ((1f - brightness) * panelHeightPx).coerceIn(
                thumbSizePx / 2,
                panelHeightPx - thumbSizePx / 2
            )
            SelectionRing(
                modifier = Modifier
                    .size(thumbSizeDp)
                    .offset(
                        x = with(density) { (thumbX - thumbSizePx / 2).toDp() },
                        y = with(density) { (thumbY - thumbSizePx / 2).toDp() },
                    )
            )
        }
    }
}

/** 选中指示「空心圆环」：与色板网格取色点同款——6dp 白色描边圆环 + 外围柔光，中心透出所选颜色 */
@Composable
private fun SelectionRing(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(
        modifier = modifier.drawWithCache {
            val strokeWidth = with(density) { 6.dp.toPx() }
            val halfStroke = strokeWidth / 2f
            val glowSpread = with(density) { 2.dp.toPx() }
            val glowColor = Color.Black.copy(alpha = 0.25f)

            val ringCenterRadius = (size.minDimension / 2f) - halfStroke
            val gradientRadius = ringCenterRadius + halfStroke + glowSpread

            val glowBrush = Brush.radialGradient(
                colorStops = listOf(
                    ((ringCenterRadius - halfStroke - glowSpread).coerceAtLeast(0f) / gradientRadius) to Color.Transparent,
                    ((ringCenterRadius - halfStroke) / gradientRadius) to glowColor,
                    ((ringCenterRadius + halfStroke) / gradientRadius) to glowColor,
                    ((ringCenterRadius + halfStroke + glowSpread) / gradientRadius) to Color.Transparent,
                ).toTypedArray(),
                radius = gradientRadius,
            )

            onDrawBehind {
                drawCircle(brush = glowBrush, radius = gradientRadius)
                drawCircle(
                    color = Color.White,
                    radius = ringCenterRadius,
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    )
}

@Composable
private fun GradientSlider(
    modifier: Modifier,
    gradientBrush: Brush,
    position: Float,
    onPositionChanged: (Float) -> Unit,
    checkerboard: Boolean = false,
) {
    val density = LocalDensity.current
    // 与 Miuix ColorPalette 内置滑块同款：20dp 空心圆环滑块头
    val thumbSizeDp = 20.dp
    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                trackWidthPx = coords.size.width.toFloat()
            }
            .clip(RoundedCornerShape(percent = 50))
            .drawBehind {
                if (checkerboard) {
                    // 与 Miuix drawCheckerboard 同款：3dp 小格棋盘，透出「此处是透明」
                    val cell = with(density) { 3.dp.toPx() }.coerceAtLeast(1f)
                    val rows = ceil(size.height / cell).toInt().coerceAtLeast(1)
                    drawRect(color = Color(0xFFCCCCCC))
                    val darkColor = Color(0xFFAAAAAA)
                    for (row in 0 until rows) {
                        var x = if (row % 2 == 0) cell else 0f
                        while (x < size.width) {
                            drawRect(
                                color = darkColor,
                                topLeft = Offset(x, row * cell),
                                size = Size(
                                    min(x + cell, size.width) - x,
                                    min((row + 1) * cell, size.height) - row * cell,
                                ),
                            )
                            x += cell * 2f
                        }
                    }
                }
                // 与色板内置滑块一致：胶囊轨道，无描边
                drawRoundRect(
                    brush = gradientBrush,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onPositionChanged(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onPositionChanged(fraction)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (trackWidthPx > 0f) {
            val thumbSizePx = with(density) { thumbSizeDp.toPx() }
            val thumbOffsetPx = (position * trackWidthPx).coerceIn(
                thumbSizePx / 2,
                trackWidthPx - thumbSizePx / 2
            )
            SelectionRing(
                modifier = Modifier
                    .size(thumbSizeDp)
                    .offset(
                        x = with(density) { (thumbOffsetPx - thumbSizePx / 2).toDp() },
                        y = 0.dp,
                    )
            )
        }
    }
}

private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val delta = max - min

    val h = when {
        max == min -> 0f
        max == r -> ((g - b) / delta + if (g < b) 6f else 0f) * 60f
        max == g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }
    val s = if (max == 0f) 0f else delta / max
    val v = max
    return floatArrayOf(h, s, v)
}

private fun normalizeHexInput(input: String): String {
    val trimmed = input.trim().uppercase()
    return if (trimmed.startsWith("#")) {
        "#${trimmed.removePrefix("#")}"
    } else {
        trimmed
    }
}

private fun parseHexColor(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length !in setOf(6, 8) || !hex.isHex()) return null
    val argb = if (hex.length == 6) "FF$hex" else hex
    return argb.toLong(16).toInt()
}

private fun Int.asHexColorString(): String =
    "#${Integer.toHexString(this).uppercase().padStart(8, '0')}"
