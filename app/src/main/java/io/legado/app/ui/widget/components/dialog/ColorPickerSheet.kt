package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
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
    var isPaletteMode by remember { mutableStateOf(true) }

    LaunchedEffect(show, initialColor) {
        if (show) {
            currentColor = Color(initialColor)
            hexInput = initialColor.asHexColorString()
            isHexInputError = false
            isPaletteMode = true
        }
    }

    val parsedHexColor = parseHexColor(hexInput)

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
            MediumTonalButton(
                onClick = {
                    onColorSelected(currentColor.toArgb())
                    onDismissRequest()
                },
                enabled = parsedHexColor != null && !isHexInputError,
                icon = Icons.Default.Save,
                contentDescription = stringResource(R.string.action_save),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { isPaletteMode = !isPaletteMode }) {
                    Icon(
                        imageVector = if (isPaletteMode) Icons.Outlined.Tune else Icons.Outlined.GridView,
                        contentDescription = if (isPaletteMode) {
                            stringResource(R.string.color_mixer)
                        } else {
                            stringResource(R.string.color_palette)
                        },
                        tint = LegadoTheme.colorScheme.primary,
                    )
                }
            }

            when (isPaletteMode) {
                true -> PaletteMode(
                    currentColor = currentColor,
                    onColorChanged = { color ->
                        currentColor = color
                        hexInput = color.toArgb().asHexColorString()
                        isHexInputError = false
                    },
                )
                false -> MixerMode(
                    currentColor = currentColor,
                    onColorChanged = { color ->
                        currentColor = color
                        hexInput = color.toArgb().asHexColorString()
                        isHexInputError = false
                    },
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

@Composable
private fun PaletteMode(
    currentColor: Color,
    onColorChanged: (Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ColorPalette(
            color = currentColor,
            onColorChanged = onColorChanged,
            rows = 8,
            hueColumns = 12,
            modifier = Modifier.fillMaxWidth(),
            showPreview = false
        )

        Spacer(modifier = Modifier.height(20.dp))

        val hsv = remember(currentColor) { colorToHsv(currentColor) }
        var alpha by remember(currentColor) { mutableFloatStateOf(currentColor.alpha) }

        SliderLabel(text = stringResource(R.string.color_alpha))
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    currentColor.copy(alpha = 0f),
                    currentColor.copy(alpha = 1f),
                )
            ),
            position = alpha,
            onPositionChanged = { newAlpha ->
                alpha = newAlpha
                onColorChanged(Color.hsv(hsv[0], hsv[1], hsv[2], newAlpha))
            },
        )
    }
}

@Composable
private fun MixerMode(
    currentColor: Color,
    onColorChanged: (Color) -> Unit,
) {
    val hsv = remember(currentColor) { colorToHsv(currentColor) }
    var hue by remember(currentColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(currentColor) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(currentColor) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(currentColor) { mutableFloatStateOf(currentColor.alpha) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SliderLabel(text = stringResource(R.string.color_hue))
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) }
            ),
            position = hue / 360f,
            onPositionChanged = { pos ->
                hue = pos * 360f
                onColorChanged(Color.hsv(hue, saturation, brightness, alpha))
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        SliderLabel(text = stringResource(R.string.color_saturation))
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.hsv(hue, 0f, brightness),
                    Color.hsv(hue, 1f, brightness),
                )
            ),
            position = saturation,
            onPositionChanged = { pos ->
                saturation = pos
                onColorChanged(Color.hsv(hue, saturation, brightness, alpha))
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        SliderLabel(text = stringResource(R.string.color_brightness))
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color.hsv(hue, saturation, 0f),
                    Color.hsv(hue, saturation, 1f),
                )
            ),
            position = brightness,
            onPositionChanged = { pos ->
                brightness = pos
                onColorChanged(Color.hsv(hue, saturation, brightness, alpha))
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        val previewColor = Color.hsv(hue, saturation, brightness, 1f)
        SliderLabel(text = stringResource(R.string.color_alpha))
        GradientSlider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp),
            gradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    previewColor.copy(alpha = 0f),
                    previewColor.copy(alpha = 1f),
                )
            ),
            position = alpha,
            onPositionChanged = { pos ->
                alpha = pos
                onColorChanged(Color.hsv(hue, saturation, brightness, alpha))
            },
        )
    }
}

@Composable
private fun GradientSlider(
    modifier: Modifier,
    gradientBrush: Brush,
    position: Float,
    onPositionChanged: (Float) -> Unit,
) {
    val thumbColor = LegadoTheme.colorScheme.onSurface
    val outlineColor = LegadoTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val thumbSizeDp = 28.dp
    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords: LayoutCoordinates ->
                trackWidthPx = coords.size.width.toFloat()
            }
            .clip(RoundedCornerShape(18.dp))
            .drawBehind {
                drawRoundRect(
                    brush = gradientBrush,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                )
                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
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
            Box(
                modifier = Modifier
                    .size(thumbSizeDp)
                    .offset(
                        x = with(density) { (thumbOffsetPx - thumbSizePx / 2).toDp() },
                        y = 0.dp,
                    )
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(thumbColor)
                    .border(2.dp, outlineColor, CircleShape),
            )
        }
    }
}

@Composable
private fun SliderLabel(text: String) {
    AppText(
        text = text,
        style = LegadoTheme.typography.labelMedium,
        color = LegadoTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
    )
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
