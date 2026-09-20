package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.data.entities.HighlightRule
import io.legado.app.domain.model.MarkingEffect
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.feature.reader.core.selection.ReaderSelectionMenuAnchor
import io.legado.app.ui.book.read.DefaultMarkingStyle
import io.legado.app.ui.book.read.MarkingUiState
import io.legado.app.ui.book.read.TextMenuPositionProvider
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 划线/高亮笔记配置 Sheet。
 *
 * 样式来源两种：复用用户已有的一条高亮规则（[MarkingUiState.highlightRules]），
 * 或直接自定义本次的样式——上面一行预设颜色（尾部为自定义颜色），下面 5x1 效果格
 * （单实线/波浪线/虚线/背景色/字体色）。背景色自动加 ~20% 透明度。另带备注输入。
 * 两种进入方式：
 * - 新增：选中文本后点「笔记」（[MarkingUiState.selection]），样式预选 [DefaultMarkingStyle]；
 * - 编辑：点正文已有划线，或从目录 Sheet 点标记项
 *   （[MarkingUiState.editing] 非空），预填样式与备注，保存即更新；编辑模式可删除。
 *
 * 呈现形态由 [MarkingUiState.floatingAnchor] 决定：阅读页入口（划词菜单/点正文划线）
 * 知道笔记在正文中的位置，弹层悬浮在该位置旁——改样式时正文里的实时预览不被挡住；
 * 目录等无位置入口仍是标准底部弹层。两种形态共用同一套标题行与内容组件，
 * 悬浮形态为压低高度默认收起「样式来源」区（下拉 + 规则列表，由标题行调色按钮展开/收起），
 * 颜色行与效果格仍内联展示；调色与分享卡片放标题行右侧，删除单独放左侧。
 * 没有保存按钮：关闭弹层即保存本次改动（样式或备注与会话初始值不同时才落库）。
 */
@Composable
fun MarkingSheet(
    show: Boolean,
    state: MarkingUiState,
    onDismissRequest: () -> Unit,
    onSave: (style: TextProcessStyle, note: String) -> Unit,
    onDelete: () -> Unit,
    showStyleConfig: Boolean = true,
    onStylePreview: (TextProcessStyle) -> Unit = {},
    onGenerateShareCard: (() -> Unit)? = null,
) {
    val selection = state.selection
    val editing = state.editing

    // 标题用位置（章节名），与书签对话框一致；原文由正文实时预览直接呈现，
    // 弹层不再重复展示。
    val position = selection?.chapterName?.takeIf { it.isNotBlank() } ?: editing?.chapterName ?: ""

    // 状态提升到 Sheet 顶层：底部 ColorPickerSheet 与内容共用；以 show + editing 为键，
    // 每次打开/切到编辑模式时重置（编辑模式的样式/颜色/备注来自已有标记）。
    val editingStyle = remember(editing) {
        editing?.styleJson?.let { GSON.fromJsonObject<TextProcessStyle>(it).getOrNull() }
    }
    // 新建预选「笔记默认样式」（HighlightRuleConfigSheet 里那份独立默认），
    // 编辑已有标记时用其实际存储的样式，两种情况都无需每次重选。
    val baseStyle = editingStyle ?: remember(show) { DefaultMarkingStyle.get() }
    var useRule by remember(show, editing) { mutableStateOf(false) }
    var selectedRuleId by remember(show, editing) { mutableStateOf<String?>(null) }
    // 5x1 效果格 + 选中颜色：从基准样式反推
    var effect by remember(show, editing) {
        mutableStateOf(MarkingEffect.fromStyle(baseStyle))
    }
    var markColor by remember(show, editing) {
        mutableStateOf(MarkingEffect.colorOf(baseStyle))
    }
    // 隐藏透传：仅编辑下划线类标记时保留已有宽度/偏移/SVG（自定义模式没有这些控件）；
    // 新建走 effect 的规范值，避免存量脏宽度传染给新笔记
    var underlineWidth by remember(show, editing) {
        mutableStateOf(editingStyle?.underlineWidth ?: 1f)
    }
    var underlineOffset by remember(show, editing) {
        mutableStateOf(editingStyle?.underlineOffset ?: 2f)
    }
    var underlineSvgPath by remember(show, editing) {
        mutableStateOf(editingStyle?.underlineSvgPath)
    }
    var showColorPicker by remember(show, editing) { mutableStateOf(false) }
    // 悬浮形态默认收起「样式来源」区（下拉 + 规则列表）：这套规则选择器较长，
    // 收进标题行的调色按钮后，面板只剩颜色行、效果格与备注，紧贴笔记位置不挡正文。
    // 颜色行与效果格仍按原样内联展示。底部弹层没有高度顾虑，来源区始终展示。
    val floating = show && state.floatingAnchor != null
    var styleExpanded by remember(show, editing) { mutableStateOf(false) }
    val noteState = key(show, editing) {
        rememberTextFieldState(initialText = editing?.note ?: "")
    }
    // Sheet 当前拼出的样式：既是保存用的最终值，也是正文选区的实时预览值。
    // 会话内才上报（新建走划词选区，编辑走点正文划线时画布建的标记选区）；
    // 打开即以预选样式上报一次，「样式瞬间出现」从这里来——纯预览不落库，
    // 不会留下半途笔记。
    val currentStyle = buildStyle(
        useRule = useRule,
        selectedRule = state.highlightRules.firstOrNull { it.id == selectedRuleId },
        effect = effect,
        markColor = markColor,
        underlineWidth = underlineWidth,
        underlineOffset = underlineOffset,
        underlineSvgPath = underlineSvgPath,
    )
    if (show && (selection != null || editing != null)) {
        LaunchedEffect(currentStyle) { onStylePreview(currentStyle) }
    }

    // 会话初始快照：样式状态与备注在同一 (show, editing) 键下重置，键变化后的首次
    // 组合就是「用户什么都没动」时的值。关闭时与之比较，没动过就不落库。
    val baseline = remember(show, editing) { currentStyle to noteState.text.toString() }
    val dirty = currentStyle != baseline.first ||
            noteState.text.toString() != baseline.second
    // 关闭即保存：改过样式/备注则关闭动作直接等价于保存（onSave 成功后由
    // delegate 负责收起弹层），没改过则纯关闭。标题行因此不再需要保存按钮。
    val requestClose: () -> Unit = {
        if (dirty) onSave(currentStyle, noteState.text.toString()) else onDismissRequest()
    }

    // 左侧动作：仅删除（编辑模式）。删除是破坏性操作，单独放左侧，
    // 与右侧的调色/分享按钮隔开标题，降低误触。
    val startAction: @Composable (() -> Unit)? =
        if (editing != null) {
            {
                MediumTonalButton(
                    onClick = onDelete,
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete)
                )
            }
        } else null
    // 右侧动作：样式来源区展开开关（仅悬浮形态）+ 分享卡片。
    // 保存按钮已按「关闭即保存」语义移除。
    val showStyleToggle = floating && showStyleConfig
    val endAction: (@Composable () -> Unit)? =
        if (showStyleToggle || onGenerateShareCard != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showStyleToggle) {
                        MediumTonalButton(
                            onClick = { styleExpanded = !styleExpanded },
                            icon = Icons.Default.Palette,
                            contentDescription = stringResource(R.string.bookmark_mark_style_source)
                        )
                    }
                    onGenerateShareCard?.let { share ->
                        MediumTonalButton(
                            onClick = share,
                            icon = Icons.Outlined.Image,
                            contentDescription = stringResource(R.string.generate_share_card)
                        )
                    }
                }
            }
        } else null
    // 底部弹层与悬浮两种形态共用的主体内容。
    val body: @Composable ColumnScope.() -> Unit = {
        if (selection == null && editing == null) {
            // 编辑模式异步加载标记期间的占位，避免先空再弹内容
            if (state.loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCircularProgressIndicator()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 悬浮形态下只收起「样式来源」（下拉 + 规则列表），由标题行的调色按钮展开；
                // 颜色行与效果格始终按原样内联展示。
                if (showStyleConfig) {
                    if (!floating || styleExpanded) {
                        TinyDropdownSettingItem(
                            title = stringResource(R.string.bookmark_mark_style_source),
                            selectedValue = if (useRule) STYLE_SOURCE_RULE else STYLE_SOURCE_CUSTOM,
                            displayEntries = arrayOf(
                                stringResource(R.string.bookmark_mark_reuse_rule),
                                stringResource(R.string.bookmark_mark_custom),
                            ),
                            entryValues = arrayOf(STYLE_SOURCE_RULE, STYLE_SOURCE_CUSTOM),
                            onValueChange = { useRule = it == STYLE_SOURCE_RULE },
                        )

                        if (useRule) {
                            val rules = state.highlightRules
                            if (rules.isEmpty()) {
                                EmptyMessage(
                                    message = stringResource(R.string.bookmark_mark_no_rules),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                )
                            } else {
                                rules.forEach { rule ->
                                    val selected = selectedRuleId == rule.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedRuleId = if (selected) null else rule.id
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (selected) {
                                                LegadoTheme.colorScheme.primary
                                            } else {
                                                LegadoTheme.colorScheme.outlineVariant
                                            },
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            AppText(
                                                text = rule.name.ifBlank { rule.displayPattern() },
                                                style = LegadoTheme.typography.bodyMedium,
                                            )
                                            AppText(
                                                text = rule.styleSummary(),
                                                style = LegadoTheme.typography.labelSmall,
                                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!useRule) {
                        // 自定义：预设颜色行（尾部自定义色）+ 5x1 效果格
                        MarkingColorRow(
                            selectedColor = markColor,
                            onColorSelected = { markColor = it },
                            onCustomColorClick = { showColorPicker = true },
                        )
                        MarkingEffectGrid(
                            selectedEffect = effect,
                            onEffectSelected = { effect = it },
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }

                // 备注（笔记）
                AppTextField(
                    state = noteState,
                    modifier = Modifier
                        .fillMaxWidth(),
                    label = stringResource(R.string.bookmark_mark_note),
                    placeholder = {
                        AppText(stringResource(R.string.bookmark_mark_note_hint))
                    },
                )
            }
        }
    }

    val floatingAnchor = if (show) state.floatingAnchor else null
    if (floatingAnchor != null) {
        // 阅读页入口：面板悬浮在笔记/选区旁，改样式时正文预览不被挡住
        MarkingFloatingPanel(
            anchor = floatingAnchor,
            title = position,
            startAction = startAction,
            endAction = endAction,
            onDismissRequest = requestClose,
            content = body,
        )
    } else {
        AppModalBottomSheet(
            show = show,
            onDismissRequest = requestClose,
            title = position,
            startAction = startAction,
            endAction = endAction,
            content = body,
        )
    }

    if (showStyleConfig) {
        ColorPickerSheet(
            show = showColorPicker,
            initialColor = markColor,
            onDismissRequest = { showColorPicker = false },
            onColorSelected = { color ->
                markColor = color
                showColorPicker = false
            },
        )
    }
}

/**
 * 笔记面板的阅读页悬浮形态：与底部弹层同一套标题行（位置 + 删除/调色按钮）和内容
 * 组件，只是挂在笔记/选区旁边且不压暗正文，改样式时能直接看到正文里的效果。
 * 定位复用划词菜单的 [TextMenuPositionProvider]，与选区菜单保持同一套挂位规则。
 */
@Composable
private fun MarkingFloatingPanel(
    anchor: ReaderSelectionMenuAnchor,
    title: String,
    startAction: (@Composable () -> Unit)?,
    endAction: (@Composable () -> Unit)?,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shadowPadding = 12.dp
    val windowSize = LocalWindowInfo.current.containerSize
    val panelWidth = with(density) { windowSize.width.toDp() } - 32.dp
    val panelMaxHeight = with(density) { (windowSize.height * 0.6f).toDp() }
    val positionProvider = remember(anchor, density.density) {
        TextMenuPositionProvider(
            density = density.density,
            startX = anchor.startX.toInt(),
            startTopY = anchor.startTopY.toInt(),
            startBottomY = anchor.startBottomY.toInt(),
            endX = anchor.endX.toInt(),
            endBottomY = anchor.endBottomY.toInt(),
            shadowPadding = with(density) { shadowPadding.roundToPx() },
            placeOppositeHalf = true,
        )
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            // 备注输入需要键盘；返回键与点击面板外都按「关闭笔记」处理，
            // 与底部弹层的取消语义一致。
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        ProvideAppDensity {
            Box(modifier = Modifier.padding(shadowPadding)) {
                NormalCard(
                    modifier = Modifier.width(panelWidth),
                    containerColor = LegadoTheme.colorScheme.surfaceBright,
                    elevation = 12.dp,
                    cornerRadius = 12.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = panelMaxHeight)
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        // 与 AppModalBottomSheet 标题行同款布局：标题居中，两侧动作。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (startAction != null) {
                                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                                    startAction()
                                }
                            }
                            if (title.isNotBlank()) {
                                AppText(
                                    text = title,
                                    style = LegadoTheme.typography.titleMediumEmphasized,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 56.dp),
                                )
                            }
                            if (endAction != null) {
                                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                    endAction()
                                }
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** 自定义样式区：预设颜色行（尾部为自定义颜色，打开取色器）。 */
@Composable
internal fun MarkingColorRow(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    onCustomColorClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkingPresetColors.forEach { color ->
            MarkingColorSwatch(
                color = color,
                selected = color == selectedColor,
                onClick = { onColorSelected(color) },
            )
        }
        // 尾部自定义颜色：未选自定义色时显示取色图标，选中后显示该色并高亮
        val isCustom = selectedColor !in MarkingPresetColors
        MarkingColorSwatch(
            color = if (isCustom) selectedColor else null,
            selected = isCustom,
            onClick = onCustomColorClick,
            custom = true,
        )
    }
}

@Composable
private fun MarkingColorSwatch(
    color: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    custom: Boolean = false,
) {
    val borderColor = if (selected) {
        LegadoTheme.colorScheme.primary
    } else {
        LegadoTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (color != null) Color(color) else LegadoTheme.colorScheme.surfaceContainerHigh
            )
            .border(2.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (custom && color == null) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = stringResource(R.string.bookmark_mark_custom_color),
                tint = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 6 选 1 效果组：单实线 / 波浪线 / 虚线 / 荧光笔 / 背景色 / 字体色。
 *
 * 连接式外观（首尾大圆角、中间小圆角、间隔 2dp 连成一体），但容器色自己给：
 * M3 ToggleButton 未选中态用的是 surface 系颜色，和 AppModalBottomSheet 的底色撞车，
 * 未选中的按钮会整个隐形、只看得见选中那一个。
 * 语义按 RadioButton，读屏能识别成单选。
 */
@Composable
internal fun MarkingEffectGrid(
    selectedEffect: MarkingEffect,
    onEffectSelected: (MarkingEffect) -> Unit,
) {
    val entries = MarkingEffect.entries
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            val selected = entry == selectedEffect
            val shape = when (index) {
                0 -> RoundedCornerShape(
                    topStart = 12.dp, bottomStart = 12.dp,
                    topEnd = 4.dp, bottomEnd = 4.dp,
                )

                entries.lastIndex -> RoundedCornerShape(
                    topStart = 4.dp, bottomStart = 4.dp,
                    topEnd = 12.dp, bottomEnd = 12.dp,
                )

                else -> RoundedCornerShape(4.dp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 32dp：M3 XSmall 按钮的容器高度，也是这个组件最初的实际高度
                    // （labelMedium 行高 16sp + 上下各 8dp）。定死避免随文字撑高。
                    .height(32.dp)
                    .clip(shape)
                    .background(
                        if (selected) {
                            LegadoTheme.colorScheme.secondaryContainer
                        } else {
                            LegadoTheme.colorScheme.surfaceContainerLow
                        }
                    )
                    .clickable { onEffectSelected(entry) }
                    .semantics { role = Role.RadioButton }
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = stringResource(entry.labelRes()),
                    style = LegadoTheme.typography.labelMediumEmphasized,
                    color = if (selected) {
                        LegadoTheme.colorScheme.onSecondaryContainer
                    } else {
                        LegadoTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 效果标签：供 [MarkingEffectGrid] 与目录 Sheet 笔记项共用。 */
internal fun MarkingEffect.labelRes(): Int = when (this) {
    MarkingEffect.SOLID -> R.string.bookmark_mark_effect_solid
    MarkingEffect.WAVE -> R.string.bookmark_mark_effect_wave
    MarkingEffect.DASHED -> R.string.bookmark_mark_effect_dash
    MarkingEffect.STRIKE -> R.string.bookmark_mark_effect_strike
    MarkingEffect.HIGHLIGHT -> R.string.bookmark_mark_effect_highlight
    MarkingEffect.BG -> R.string.bookmark_mark_effect_bg
    MarkingEffect.TEXT -> R.string.bookmark_mark_effect_text
}

/** 从标记的 styleJson 反推效果（目录 Sheet 笔记项展示用）。 */
internal fun MarkingEffect.Companion.fromStyleJson(styleJson: String?): MarkingEffect {
    val style = styleJson?.let { GSON.fromJsonObject<TextProcessStyle>(it).getOrNull() }
    return fromStyle(style)
}

private fun buildStyle(
    useRule: Boolean,
    selectedRule: HighlightRule?,
    effect: MarkingEffect,
    markColor: Int,
    underlineWidth: Float,
    underlineOffset: Float,
    underlineSvgPath: String?,
): TextProcessStyle {
    if (useRule && selectedRule != null) {
        return selectedRule.toProcessStyle()
    }
    val base = effect.toStyle(markColor)
    return if (effect.isUnderline) {
        base.copy(
            underlineWidth = underlineWidth,
            underlineOffset = underlineOffset,
            underlineSvgPath = underlineSvgPath,
        )
    } else {
        base
    }
}

private fun HighlightRule.toProcessStyle(): TextProcessStyle = TextProcessStyle(
    textColor = textColor,
    bgColor = bgColor,
    underlineMode = underlineMode,
    underlineColor = underlineColor,
    underlineWidth = underlineWidth,
    underlineOffset = underlineOffset,
    underlineSvgPath = underlineSvgPath,
)

/** 自定义模式的预设颜色（尾部之外的自定义色用取色器）。 */
private val MarkingPresetColors = listOf(
    0xFFFF5252.toInt(),
    0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(),
    0xFF26A6D6.toInt(),
    0xFF2196F3.toInt(),
    0xFF9C27B0.toInt(),
    0xFFEC407A.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt(),
)

private const val STYLE_SOURCE_RULE = "rule"
private const val STYLE_SOURCE_CUSTOM = "custom"
