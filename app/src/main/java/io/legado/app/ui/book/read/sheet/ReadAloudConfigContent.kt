package io.legado.app.ui.book.read.sheet

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.ReadAloudContentSplitSetting
import io.legado.app.domain.model.readaloud.ReadAloudSplitSymbol
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.ui.book.read.ReadBookUiState
import io.legado.app.ui.book.readaloud.config.ReadAloudConfigIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.widget.components.AppSlider
import io.legado.app.ui.widget.components.button.ConfirmDismissButtonsRow
import io.legado.app.ui.widget.components.pager.pagerHeight
import io.legado.app.ui.widget.components.pager.rememberPagerAnimatedHeight
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.tabRow.CardTabRow
import io.legado.app.ui.widget.components.text.AppText
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch

/**
 * 朗读设置页的内容（两个页签）。
 *
 * 宿主是 Navigation 3 整页 `ReadAloudConfigScreen`；数值项就地铺成滑块，
 * 不再叠一层选择器弹层——弹层套弹层正是当初层级跳乱的根源。
 */
@Composable
fun ReadAloudConfigContent(
    state: ReadBookUiState,
    playerState: ReadAloudPlayerUiState,
    onIntent: (ReadAloudConfigIntent) -> Unit,
    onPlayerIntent: (ReadAloudPlayerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }
    val animatedHeight by rememberPagerAnimatedHeight(pagerState, pageHeights)
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardTabRow(
            modifier = modifier,
            tabTitles = listOf(
                stringResource(R.string.read_aloud_settings_general_tab),
                stringResource(R.string.read_aloud_settings_voice_tab),
            ),
            selectedTabIndex = pagerState.currentPage,
            onTabSelected = { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            }
        )
        HorizontalPager(
            state = pagerState,
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .pagerHeight(animatedHeight),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> pageHeights[page] = size.height }
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                if (page == 0) {
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.default_read_aloud_interface),
                        selectedValue = state.defaultReadAloudInterface,
                        displayEntries = arrayOf(
                            stringResource(R.string.read_aloud_interface_classic),
                            stringResource(R.string.read_aloud_interface_player),
                        ),
                        entryValues = arrayOf("classic", "player"),
                        description = stringResource(R.string.default_read_aloud_interface_summary),
                        onValueChange = { onIntent(ReadAloudConfigIntent.SetDefaultInterface(it)) },
                    )
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.read_aloud_player_background),
                        selectedValue = playerState.bgMode.toString(),
                        displayEntries = arrayOf(
                            stringResource(R.string.read_aloud_bg_solid),
                            stringResource(R.string.read_aloud_bg_blur),
                            stringResource(R.string.read_aloud_bg_flowing_light),
                            stringResource(R.string.read_aloud_bg_transparent),
                        ),
                        entryValues = arrayOf(
                            ReadAloudBgMode.Solid.toString(),
                            ReadAloudBgMode.Blur.toString(),
                            ReadAloudBgMode.FlowingLight.toString(),
                            ReadAloudBgMode.Transparent.toString(),
                        ),
                        onValueChange = { onPlayerIntent(ReadAloudPlayerIntent.SetBgMode(it.toInt())) },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.show_read_aloud_capsule),
                        description = stringResource(R.string.show_read_aloud_capsule_summary),
                        checked = state.showReadAloudCapsule,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.ShowCapsule(it))
                        },
                    )
                    if (state.showReadAloudCapsule) {
                        TinySwitchSettingItem(
                            title = stringResource(R.string.capsule_auto_collapse),
                            description = stringResource(R.string.capsule_auto_collapse_summary),
                            checked = state.capsuleAutoCollapse,
                            onCheckedChange = {
                                onIntent(ReadAloudConfigIntent.CapsuleAutoCollapse(it))
                            },
                        )
                    }
                    TinySwitchSettingItem(
                        title = stringResource(R.string.ignore_audio_focus_title),
                        description = stringResource(R.string.ignore_audio_focus_summary),
                        checked = state.readAloudIgnoreAudioFocus,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.IgnoreAudioFocus(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.pause_read_aloud_while_phone_calls_title),
                        description = stringResource(R.string.pause_read_aloud_while_phone_calls_summary),
                        checked = state.readAloudPauseOnPhoneCall,
                        enabled = state.readAloudIgnoreAudioFocus,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.PauseOnPhoneCall(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_wake_lock),
                        description = stringResource(R.string.read_aloud_wake_lock_summary),
                        checked = state.readAloudWakeLock,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.WakeLock(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_keep_on_exit),
                        description = stringResource(R.string.read_aloud_keep_on_exit_summary),
                        checked = state.readAloudKeepOnExit,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.KeepOnExit(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.pref_media_button_per_next),
                        description = stringResource(R.string.pref_media_button_per_next_summary),
                        checked = state.readAloudMediaButtonPerNext,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.MediaButtonPerNext(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_android_media_control),
                        description = stringResource(R.string.read_aloud_android_media_control_summary),
                        checked = state.readAloudAndroidMediaControl,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.AndroidMediaControl(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.system_media_control_compatibility_change),
                        description = stringResource(R.string.system_media_control_compatibility_change_summary),
                        checked = state.readAloudSystemMediaCompat,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.SystemMediaCompat(it))
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.stream_read_aloud_audio),
                        description = stringResource(R.string.stream_read_aloud_audio_summary),
                        checked = state.readAloudStreamAudio,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.StreamAudio(it))
                        },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.reset_read_aloud_capsule_position),
                        description = stringResource(R.string.reset_read_aloud_capsule_position_summary),
                        onClick = { onIntent(ReadAloudConfigIntent.ResetCapsulePosition) },
                    )
                } else {
                    TinyClickableSettingItem(
                        title = stringResource(R.string.read_aloud_engines_and_voices),
                        description = stringResource(R.string.read_aloud_engines_and_voices_summary),
                        onClick = { onIntent(ReadAloudConfigIntent.OpenEnginesAndVoices) },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_cache_manage),
                        description = stringResource(R.string.tts_cache_manage_summary),
                        onClick = { onIntent(ReadAloudConfigIntent.OpenTtsCache) },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.read_aloud_character_casting),
                        description = stringResource(R.string.book_voice_casting_entry_summary),
                        onClick = { onIntent(ReadAloudConfigIntent.OpenBookVoiceCasting) },
                    )
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.speech_analysis_mode),
                        selectedValue = state.speechAnalysisMode,
                        displayEntries = arrayOf(
                            stringResource(R.string.speech_analysis_rule),
                            stringResource(R.string.speech_analysis_rule_ai),
                            stringResource(R.string.speech_analysis_ai),
                        ),
                        entryValues = arrayOf("rule", "rule_with_ai", "ai_understanding"),
                        description = when (state.speechAnalysisMode) {
                            "rule_with_ai" -> stringResource(R.string.speech_analysis_rule_ai_summary)
                            "ai_understanding" -> stringResource(R.string.speech_analysis_ai_summary)
                            else -> stringResource(R.string.speech_analysis_rule_summary)
                        },
                        onValueChange = { onIntent(ReadAloudConfigIntent.SetSpeechAnalysisMode(it)) },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.speech_storyboard),
                        description = stringResource(R.string.speech_storyboard_desc),
                        onClick = { onIntent(ReadAloudConfigIntent.OpenSpeechStoryboard) },
                    )
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.speech_analysis_reasoning_level),
                        selectedValue = state.speechAnalysisReasoningLevel,
                        displayEntries = arrayOf(
                            stringResource(R.string.ai_thinking_off),
                            stringResource(R.string.ai_thinking_auto),
                            stringResource(R.string.ai_reasoning_level_low),
                            stringResource(R.string.ai_reasoning_level_medium),
                            stringResource(R.string.ai_reasoning_level_high),
                            stringResource(R.string.ai_reasoning_level_xhigh),
                            stringResource(R.string.ai_reasoning_level_max),
                        ),
                        entryValues = AiReasoningLevel.entries
                            .map { it.storageValue }
                            .toTypedArray(),
                        description = stringResource(
                            R.string.speech_analysis_reasoning_level_summary
                        ),
                        onValueChange = {
                            onIntent(ReadAloudConfigIntent.SetSpeechAnalysisReasoningLevel(it))
                        },
                    )
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.read_aloud_content_split_mode),
                        selectedValue = state.readAloudContentSplitMode,
                        displayEntries = arrayOf(
                            stringResource(R.string.read_aloud_content_split_default),
                            stringResource(R.string.read_aloud_content_split_paragraph),
                            stringResource(R.string.read_aloud_content_split_page),
                            stringResource(R.string.read_aloud_content_split_symbols),
                        ),
                        entryValues = ReadAloudContentSplitMode.entries
                            .map { it.storageValue }
                            .toTypedArray(),
                        description = when (state.readAloudContentSplitMode) {
                            ReadAloudContentSplitMode.Paragraph.storageValue ->
                                stringResource(R.string.read_aloud_content_split_paragraph_summary)

                            ReadAloudContentSplitMode.Page.storageValue ->
                                stringResource(R.string.read_aloud_content_split_page_summary)

                            ReadAloudContentSplitMode.Symbols.storageValue ->
                                stringResource(R.string.read_aloud_content_split_symbols_summary)

                            else ->
                                stringResource(R.string.read_aloud_content_split_default_summary)
                        },
                        onValueChange = { value ->
                            onIntent(
                                ReadAloudConfigIntent.SetContentSplitMode(
                                    ReadAloudContentSplitSetting.encode(
                                        mode = ReadAloudContentSplitMode.fromStorage(value),
                                        symbols = state.readAloudContentSplitSymbols
                                            .mapNotNull { it.firstOrNull() }
                                            .ifEmpty { ReadAloudSplitSymbol.sentenceEnds },
                                    )
                                )
                            )
                        },
                    )
                    if (state.readAloudContentSplitMode ==
                        ReadAloudContentSplitMode.Symbols.storageValue
                    ) {
                        // 未显式保存过标点时实际生效的是默认句末标点，界面必须显示同一集合，
                        // 否则勾选框全空、朗读却仍按句末标点切分。
                        val selected = state.readAloudContentSplitSymbols
                            .mapNotNull { it.firstOrNull() }
                            .toSet()
                            .ifEmpty { ReadAloudSplitSymbol.sentenceEnds }
                        ContentSplitSymbolSettingItem(
                            selectedSymbols = selected.map(Char::toString).toImmutableSet(),
                            onToggle = { symbol, checked ->
                                // 至少保留一个标点：全部取消会让「按符号」退化成整段
                                val next = if (checked) selected + symbol else selected - symbol
                                if (next.isNotEmpty()) {
                                    onIntent(
                                        ReadAloudConfigIntent.SetContentSplitMode(
                                            ReadAloudContentSplitSetting.encode(
                                                mode = ReadAloudContentSplitMode.Symbols,
                                                symbols = next,
                                            )
                                        )
                                    )
                                }
                            },
                        )
                    }
                    TinySwitchSettingItem(
                        title = stringResource(R.string.use_multi_speaker),
                        description = stringResource(R.string.use_multi_speaker_summary),
                        checked = state.useMultiSpeaker,
                        onCheckedChange = {
                            onIntent(ReadAloudConfigIntent.SetUseMultiSpeaker(it))
                        },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.sys_tts_config),
                        onClick = { onIntent(ReadAloudConfigIntent.OpenSystemTtsSettings) },
                    )
                    ReadAloudNumberSliderItem(
                        title = stringResource(R.string.read_aloud_preload),
                        description = stringResource(
                            R.string.read_aloud_preload_summary, state.preDownloadNum,
                        ),
                        value = state.preDownloadNum,
                        defaultValue = 10,
                        valueRange = 0f..100f,
                        onValueChange = { onIntent(ReadAloudConfigIntent.SetPreDownloadNum(it)) },
                    )
                    ReadAloudNumberSliderItem(
                        title = stringResource(R.string.tts_pre_synthesis_concurrency),
                        description = stringResource(
                            R.string.tts_pre_synthesis_concurrency_summary,
                            state.preSynthesisConcurrency,
                        ),
                        value = state.preSynthesisConcurrency,
                        defaultValue = 3,
                        valueRange = 1f..8f,
                        onValueChange = {
                            onIntent(ReadAloudConfigIntent.SetPreSynthesisConcurrency(it))
                        },
                    )
                    ReadAloudNumberSliderItem(
                        title = stringResource(R.string.tts_paragraph_interval),
                        description = stringResource(
                            R.string.tts_paragraph_interval_summary,
                            state.readAloudParagraphInterval,
                        ),
                        value = state.readAloudParagraphInterval,
                        defaultValue = 0,
                        valueRange = 0f..5000f,
                        onValueChange = {
                            onIntent(ReadAloudConfigIntent.SetParagraphInterval(it))
                        },
                    )
                    ReadAloudNumberSliderItem(
                        title = stringResource(R.string.audio_cache_clean_time),
                        description = stringResource(
                            R.string.audio_cache_clean_time_summary,
                            state.audioCacheCleanTime,
                        ),
                        descriptionMaxLines = 2,
                        value = state.audioCacheCleanTime,
                        defaultValue = 10,
                        valueRange = 0f..10080f,
                        onValueChange = {
                            onIntent(ReadAloudConfigIntent.SetAudioCacheCleanTime(it))
                        },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.clear_cache),
                        onClick = { onIntent(ReadAloudConfigIntent.ClearTtsCache) },
                    )
                }
            }
        }
    }
}

/**
 * 数值项：点击展开后铺开滑块，保留精确输入与恢复默认，卡片外观与同页其它 Tiny 项一致。
 * 不再走共享的 SliderSettingItem——它是遗留的「拼块 + 分隔线」样式，夹在 Tiny 卡片里会突兀。
 */
@Composable
private fun ReadAloudNumberSliderItem(
    title: String,
    description: String,
    value: Int,
    defaultValue: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    descriptionMaxLines: Int = 1,
    onValueChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isInputMode by remember { mutableStateOf(false) }
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val textFieldState = rememberTextFieldState()

    // 朗读数值均为整数, 拖动时吸附到整数
    fun snap(v: Float): Float = v.roundToInt().toFloat()
    fun format(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else v.toString()

    LaunchedEffect(value) { sliderValue = value.toFloat() }
    LaunchedEffect(isInputMode) {
        if (isInputMode) textFieldState.edit { replace(0, length, format(value.toFloat())) }
    }

    // 拖动中让标题下的数值实时跟随滑块, 松手或收起才真正写回
    val displayDescription =
        if (sliderValue != value.toFloat()) format(sliderValue) else description

    fun commitValue() {
        if (isInputMode) {
            textFieldState.text.toString().toFloatOrNull()?.let {
                onValueChange(snap(it).coerceIn(valueRange).toInt())
            }
        } else if (sliderValue != value.toFloat()) {
            onValueChange(sliderValue.toInt())
        }
    }

    TinySettingItem(
        title = title,
        description = displayDescription,
        descriptionMaxLines = descriptionMaxLines,
        expanded = expanded,
        onExpandChange = {
            if (expanded) commitValue()
            expanded = it
        },
        expandContent = {
            AnimatedContent(targetState = isInputMode, label = "readAloudNumberInput") { inputMode ->
                if (inputMode) {
                    TextField(
                        state = textFieldState,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        label = {
                            AppText(
                                stringResource(
                                    R.string.input_value_range,
                                    valueRange.start.toInt(),
                                    valueRange.endInclusive.toInt(),
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = 4.dp,
                            start = 12.dp,
                            end = 12.dp,
                        ),
                    )
                } else {
                    AppSlider(
                        value = sliderValue,
                        onValueChange = { sliderValue = snap(it) },
                        onValueChangeFinished = {
                            onValueChange(sliderValue.coerceIn(valueRange).toInt())
                        },
                        valueRange = valueRange,
                        modifier = Modifier.fillMaxWidth(),
                        accessibilityLabel = title,
                        accessibilityValue = displayDescription,
                    )
                }
            }
            ConfirmDismissButtonsRow(
                modifier = Modifier.padding(top = 16.dp),
                onDismiss = { isInputMode = !isInputMode },
                onConfirm = {
                    onValueChange(defaultValue)
                    textFieldState.edit { replace(0, length, format(defaultValue.toFloat())) }
                },
                dismissText = stringResource(
                    if (isInputMode) R.string.slider else R.string.edit
                ),
                confirmText = stringResource(R.string.text_default),
            )
        },
    )
}

/**
 * 「按符号」划分方式的标点多选。
 *
 * 至少保留一个标点：全部取消会让「按符号」退化成整段，与用户刚选的划分方式矛盾。
 */
@Composable
private fun ContentSplitSymbolSettingItem(
    selectedSymbols: ImmutableSet<String>,
    onToggle: (Char, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TinySettingItem(
        title = stringResource(R.string.read_aloud_content_split_symbols),
        description = stringResource(R.string.read_aloud_content_split_selected_symbols) + ": " +
                selectedSymbols.joinToString(" "),
        expanded = expanded,
        onExpandChange = { expanded = it },
        expandContent = {
            ReadAloudSplitSymbol.entries.forEach { option ->
                TinySwitchSettingItem(
                    title = stringResource(symbolLabelRes(option)),
                    checked = option.symbol.toString() in selectedSymbols,
                    onCheckedChange = { onToggle(option.symbol, it) },
                )
            }
        },
    )
}

private fun symbolLabelRes(option: ReadAloudSplitSymbol): Int = when (option) {
    ReadAloudSplitSymbol.FullStop -> R.string.symbol_period
    ReadAloudSplitSymbol.Exclamation -> R.string.symbol_exclamation
    ReadAloudSplitSymbol.Question -> R.string.symbol_question
    ReadAloudSplitSymbol.Ellipsis -> R.string.symbol_ellipsis
    ReadAloudSplitSymbol.Semicolon -> R.string.symbol_semicolon
    ReadAloudSplitSymbol.Comma -> R.string.symbol_comma
    ReadAloudSplitSymbol.EnumerationComma -> R.string.symbol_enumeration_comma
    ReadAloudSplitSymbol.Colon -> R.string.symbol_colon
    ReadAloudSplitSymbol.Dot -> R.string.symbol_halfwidth_period
    ReadAloudSplitSymbol.Bang -> R.string.symbol_halfwidth_exclamation
    ReadAloudSplitSymbol.QuestionMark -> R.string.symbol_halfwidth_question
    ReadAloudSplitSymbol.HalfSemicolon -> R.string.symbol_halfwidth_semicolon
    ReadAloudSplitSymbol.HalfComma -> R.string.symbol_halfwidth_comma
    ReadAloudSplitSymbol.HalfColon -> R.string.symbol_halfwidth_colon
}
