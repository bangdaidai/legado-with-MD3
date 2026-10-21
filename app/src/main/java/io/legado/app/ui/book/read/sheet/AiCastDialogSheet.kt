package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.help.readaloud.cast.AiCastPresetStore
import io.legado.app.help.readaloud.cast.AiCastProgress
import io.legado.app.help.readaloud.cast.BgmSceneStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.ai.chat.AiThinkingCard
import io.legado.app.ui.ai.chat.AiThinkingStep
import io.legado.app.ui.ai.label
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.CastFieldSpec
import io.legado.app.ui.widget.components.CastFieldStack
import io.legado.app.ui.widget.components.CastImeTextField
import io.legado.app.ui.widget.components.CollapsibleHeader
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallOutlinedButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 识别配乐场景弹层（朗读面板/底栏那颗 AI 按钮打开）。
 *
 * 角色那一趟（逐句识别+分配）已经随「AI 分配角色」入口退役（S7 切片D）：逐句归属由朗读链路
 * 产出，批量补分析落在分镜结果页，范围落在朗读设置的「顺带往后分析几章」。这里只剩配乐：
 * 它不需要先开多角色朗读，也不碰正文里的角色胶囊。
 *
 * 提示词那一套（分配要求预设、临时要求、本书角色记忆）只服务角色那趟，一并下线；
 * 场景提示词仍走 `AiCastPresetStore.scenePrompt()` 那一条固定槽位，跟分镜页的 SCENE_PROMPT 无关。
 *
 * 布局：走 AppModalBottomSheet。开始/取消运行/清除本章场景放在弹层头部的 start/endAction，
 * 内容区整体滚动，再长也不会把主操作顶到屏幕外。
 */
@Composable
fun AiCastDialogSheet(
    show: Boolean,
    /**
     * 当前推理强度，来自朗读设置那一份唯一的存储（`ReadBookUiState.speechAnalysisReasoningLevel`）。
     *
     * 这里只回显、不修改：强度全书只有朗读设置一个入口，弹窗自己那份旧存储会在首次打开时
     * 搬过去并删掉（见 [AiCastPresetStore.takeLegacyReasoningLevel]）。
     */
    reasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
    onDismissRequest: () -> Unit,
    onIntent: (ReadBookIntent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = ReadBook.book
    val bookUrl = book?.bookUrl.orEmpty()
    val chapterTotal = book?.totalChapterNum ?: 0
    val progress by AiCastProgress.state.collectAsStateWithLifecycle()

    // 当前章一律每次重组重读（与 `BgmSceneSheet`/`ReadAloudCastSheet` 同一口径）：
    // 这个弹层常挂在阅读界面上，`show` 只是开关，用 remember 存快照就会把章号钉在进阅读器
    // 那一章，切章后「清除本章场景」和发给 AI 的 currentChapter 都会打到错的章。
    val chapterIndex = ReadBook.durChapterIndex
    val chapterTitle = book?.durChapterTitle.orEmpty()
    var sceneCount by remember(chapterIndex) { mutableStateOf(-1) }
    var reassign by remember { mutableStateOf(false) }
    var extraChapters by remember { mutableIntStateOf(0) }
    // 范围模式：起止章直接选（章号从 1 开始填，与听书下载同一套口径），关掉就是「当前章 + 追加章节数」
    var byRange by remember { mutableStateOf(false) }
    // 起止章默认跟着当前章走；同章内用户手填的值要留住，所以只以 chapterIndex 为 key
    var rangeStartText by remember(chapterIndex) { mutableStateOf((chapterIndex + 1).toString()) }
    var rangeEndText by remember(chapterIndex) { mutableStateOf((chapterIndex + 1).toString()) }
    var requestOpen by remember { mutableStateOf(false) }
    // 场景提示词：折叠默认收起，展开才读库（读的是 AiCastPresetStore 那一条槽位行）
    var promptOpen by remember { mutableStateOf(false) }
    /** null = 还没读出来；读取只发生在第一次展开时，之后编辑都在这份本地值上。 */
    var promptText by remember { mutableStateOf<String?>(null) }

    /**
     * 本书上一次跑到哪儿（停在第几章、哪些章失败、上次用的范围），存在 prefs。
     *
     * 每次跑完（[AiCastProgress.State.running] 由 true 变 false）都重读一次，所以取消后
     * 「从第 N 章继续」「重试失败 N 章」这两个入口关掉重开悬浮窗照样在。写这一份的是运行侧
     * （`ReadAloudCastDelegate` 的正常收尾与取消收尾），界面只读不写。
     * 章号是 0 基，这里显示与填框一律 +1。
     */
    val storedRun by produceState(
        initialValue = AiCastPresetStore.AiCastRunState(),
        key1 = bookUrl,
        key2 = progress.running,
    ) {
        value = withContext(Dispatchers.IO) { AiCastPresetStore.loadCastRunState(bookUrl) }
    }
    // 跑完/取消后停在哪儿：优先看本次运行的进度状态，其次看书里存的那一份
    val resumeChapter = if (progress.running) AiCastPresetStore.NO_RESUME_CHAPTER else storedRun.resumeChapter
    val retryChapters = if (progress.failedChapters.isNotEmpty()) {
        progress.failedChapters
    } else {
        storedRun.failedChapters
    }

    /** 本次要分配的章区间（0 基、含两端）；null = 范围填得不合法。换算规则见 [castChapterRange]。 */
    val castRange: Pair<Int, Int>? = castChapterRange(
        byRange = byRange,
        startText = rangeStartText,
        endText = rangeEndText,
        chapterTotal = chapterTotal,
        currentChapter = chapterIndex,
        extraChapters = extraChapters,
    )

    LaunchedEffect(bookUrl) {
        if (book == null) return@LaunchedEffect
        // 老版本这个弹窗自己存过一份推理强度。第一次打开时把它搬进朗读设置、随即删掉旧键，
        // 用户表过态的选择不会静默丢；之后强度只有设置页那一份。这一趟搬完就不再回来，
        // 但入口留着比删掉安全——留着它，那些从没点开过弹窗的旧值才有机会被搬走。
        val legacyLevel = withContext(Dispatchers.IO) {
            AiCastPresetStore.takeLegacyReasoningLevel()
        }
        if (legacyLevel != null) {
            onIntent(ReadBookIntent.SetSpeechAnalysisReasoningLevel(legacyLevel))
        }
    }

    // 提示词文本第一次展开时才读：每次重组打库既没必要，也会把用户正在编辑的那份顶掉
    LaunchedEffect(promptOpen) {
        if (promptOpen && promptText == null) {
            promptText = withContext(Dispatchers.IO) { AiCastPresetStore.scenePrompt() }
        }
    }

    val running = progress.running
    /**
     * 发一次识别意图。[onlyChapters] 非空 = 重试：只跑失败的那几章（范围三兄弟由
     * `castChapterPlan` 忽略）；空 = 按界面上选中的那种模式跑整段范围（见 [castRange]）。
     */
    val startCast: (List<Int>) -> Unit = { onlyChapters ->
        val range = castRange
        val plan = when {
            onlyChapters.isNotEmpty() -> onlyChapters.first() to onlyChapters.last()
            range == null -> null
            else -> range
        }
        if (plan == null) {
            // 范围填错不是「点了没反应」：必须当场说一句，否则用户以为按钮坏了
            context.toastOnUi(R.string.read_aloud_audio_download_invalid_range)
        } else {
            onIntent(
                ReadBookIntent.StartAiCast(
                    startChapter = plan.first,
                    count = plan.second - plan.first + 1,
                    endChapter = plan.second,
                    onlyChapters = onlyChapters,
                    reassign = reassign,
                    reasoningLevel = reasoningLevel,
                ),
            )
        }
    }
    // 思考过程没有开关了：模型回了就永远显示，只是默认收起，收起时头部保留那颗秒表
    val thinkingPending = running && progress.reasoning.isBlank()
    val hasLog = progress.reasoning.isNotBlank() || progress.answer.isNotBlank() || thinkingPending
    val title = stringResource(R.string.ai_cast_scene_only)
    // 这一章有没有配乐段可清
    val deletable = sceneCount > 0
    val startAction: @Composable () -> Unit = {
        MediumTonalButton(
            onClick = {
                onIntent(ReadBookIntent.DeleteChapterBgmScenes(chapterIndex))
                sceneCount = 0
            },
            icon = AppIcons.Delete,
            contentDescription = stringResource(R.string.ai_cast_delete_scenes),
            enabled = !running && deletable,
        )
    }

    AppModalBottomSheet(
        show = show && book != null,
        onDismissRequest = onDismissRequest,
        title = title,
        startAction = startAction,
        endAction = {
            if (running) {
                MediumTonalButton(
                    onClick = { onIntent(ReadBookIntent.CancelAiCast) },
                    icon = AppIcons.Close,
                    contentDescription = stringResource(R.string.cancel),
                )
            } else {
                MediumTonalButton(
                    onClick = { startCast(emptyList()) },
                    icon = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.ai_cast_start),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 本章状态 + 总进度
            AiCastSection {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppText(
                        text = chapterTitle.ifBlank { "—" },
                        style = LegadoTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        text = if (sceneCount < 0) {
                            stringResource(R.string.ai_cast_status_loading)
                        } else {
                            stringResource(R.string.ai_cast_scene_status, sceneCount)
                        },
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                    AppText(
                        text = stringResource(R.string.ai_cast_scene_only_hint),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running || hasLog) {
                    AppLinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = if (progress.total <= 0) {
                            0f
                        } else {
                            progress.done.toFloat() / progress.total
                        },
                    )
                    AppText(
                        text = stringResource(
                            R.string.ai_cast_progress,
                            progress.done,
                            progress.total,
                        ) + progress.chapterTitle.let {
                            if (it.isBlank()) "" else " · $it"
                        },
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 跑完/取消后的那一句（「已取消，停在第 N 章」就在里面）
                progress.finishedMessage?.let {
                    AppText(
                        text = it,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 逐章失败明细：断网、连不上 AI、回复读不出来都得留一句话在这儿。
                // 失败提示在跑完之后也要继续显示，不能只在 running 时出现。
                val failureDetail = progress.failureText
                    .ifBlank { progress.lastError.orEmpty() }
                if (failureDetail.isNotBlank()) {
                    AppText(
                        text = failureDetail,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.error,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 中断后的两个入口，都不用重填范围：续跑起点填好起止章，重试只跑失败章
                if (!running && (resumeChapter >= 0 || retryChapters.isNotEmpty())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (resumeChapter >= 0) {
                            SmallOutlinedButton(
                                onClick = {
                                    byRange = true
                                    // 停在哪儿就从哪儿再来一遍（填的是显示用的 1 基章号）
                                    rangeStartText = (resumeChapter + 1).toString()
                                    rangeEndText =
                                        (storedRun.endChapter.coerceAtLeast(resumeChapter) + 1)
                                            .toString()
                                },
                                icon = Icons.Default.Restore,
                                text = stringResource(
                                    R.string.ai_cast_resume_from,
                                    resumeChapter + 1
                                ),
                            )
                        }
                        if (retryChapters.isNotEmpty()) {
                            SmallOutlinedButton(
                                enabled = !running,
                                onClick = { startCast(retryChapters) },
                                text = stringResource(
                                    R.string.ai_cast_retry,
                                    retryChapters.size
                                ),
                            )
                        }
                    }
                }
            }

            // 思考/返回内容：紧跟状态，运行时不用往下翻
            if (hasLog) {
                AiCastSection {
                    if (progress.reasoning.isNotBlank()) {
                        if (progress.reasoningFolded > 0) {
                            AppText(
                                text = stringResource(
                                    R.string.ai_cast_transcript_folded,
                                    progress.reasoningFolded,
                                ),
                                style = LegadoTheme.typography.labelSmall,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // autoExpandWhileStreaming = false：默认收起，收起时头部那颗秒表照样在走，
                        // 所以收起不等于什么都没发生
                        AiThinkingCard(
                            steps = listOf(
                                AiThinkingStep.ReasoningStep(progress.reasoning),
                            ),
                            isStreaming = running,
                            durationSeconds = progress.reasoningSeconds,
                            autoExpandWhileStreaming = false,
                        )
                    } else if (thinkingPending) {
                        AppText(
                            text = stringResource(R.string.ai_cast_thinking_pending),
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (progress.answer.isNotBlank()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AppText(
                                text = stringResource(R.string.ai_cast_answer),
                                style = LegadoTheme.typography.titleSmall,
                            )
                            val answerScroll = rememberScrollState()
                            LaunchedEffect(progress.answer) {
                                answerScroll.scrollTo(answerScroll.maxValue)
                            }
                            if (progress.answerFolded > 0) {
                                AppText(
                                    text = stringResource(
                                        R.string.ai_cast_transcript_folded,
                                        progress.answerFolded,
                                    ),
                                    style = LegadoTheme.typography.labelSmall,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(LegadoTheme.colorScheme.surfaceVariant)
                                    .verticalScroll(answerScroll)
                                    .padding(10.dp),
                            ) {
                                AppText(
                                    text = progress.answer,
                                    style = LegadoTheme.typography.bodySmall,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 请求原文：跑过一次就有。识别不对时第一个要看的就是它——
            // 是正文没送全、池子给错了，还是模型自己判错了，一眼能分开
            if (progress.request.isNotBlank()) {
                CollapsibleHeader(
                    isCollapsed = !requestOpen,
                    onToggle = { requestOpen = !requestOpen },
                    title = stringResource(R.string.ai_cast_request),
                    subtitle = stringResource(R.string.ai_cast_request_hint),
                )
                if (requestOpen) {
                    AiCastSection {
                        val requestScroll = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LegadoTheme.colorScheme.surfaceVariant)
                                .verticalScroll(requestScroll)
                                .padding(10.dp),
                        ) {
                            AppText(
                                text = progress.request,
                                style = LegadoTheme.typography.bodySmall,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 运行选项
            AiCastSection {
                TinySwitchSettingItem(
                    title = stringResource(R.string.ai_cast_reassign),
                    description = stringResource(R.string.ai_cast_reassign_summary),
                    checked = reassign,
                    enabled = !running,
                    color = Color.Transparent,
                    onCheckedChange = { reassign = it },
                )
                // 强度这一项在本弹窗只回显：全书的推理强度只有朗读设置一处可改，
                // 免得两处各存一份、各改各的（旧的那份首次打开这里会搬过去并删键）。
                AppText(
                    text = stringResource(
                        R.string.ai_cast_reasoning_level_readonly,
                        reasoningLevel.label(),
                    ),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                // 范围两种模式：「当前章 + 追加 N 章」或「指定起止章」。
                // 选中的那一种真的决定发出去的是哪些章（见 startCast / castChapterPlan）。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip(
                        label = stringResource(R.string.ai_cast_extra_chapters),
                        selected = !byRange,
                        enabled = !running,
                        onToggle = { byRange = false },
                    )
                    ToggleChip(
                        label = stringResource(R.string.ai_cast_range_mode),
                        selected = byRange,
                        enabled = !running,
                        onToggle = { byRange = true },
                    )
                }
                if (byRange) {
                    // 章号从 1 开始填；控件与文案沿用听书下载那张卡片的范围那一节
                    CastFieldStack(
                        specs = listOf(
                            CastFieldSpec(
                                id = "castStartChapter",
                                label = stringResource(
                                    R.string.read_aloud_audio_download_start_chapter,
                                ),
                                value = rangeStartText,
                                onValueChange = { rangeStartText = it },
                            ),
                            CastFieldSpec(
                                id = "castEndChapter",
                                label = stringResource(
                                    R.string.read_aloud_audio_download_end_chapter,
                                ),
                                value = rangeEndText,
                                onValueChange = { rangeEndText = it },
                            ),
                        ),
                    )
                    AppText(
                        text = if (castRange == null) {
                            stringResource(R.string.read_aloud_audio_download_invalid_range)
                        } else {
                            stringResource(
                                R.string.read_aloud_audio_download_range_count,
                                castRange.second - castRange.first + 1,
                            )
                        },
                        style = LegadoTheme.typography.bodySmall,
                        color = if (castRange == null) {
                            LegadoTheme.colorScheme.error
                        } else {
                            LegadoTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    TinySliderSettingItem(
                        title = stringResource(R.string.ai_cast_extra_chapters),
                        description = stringResource(R.string.ai_cast_extra_summary),
                        value = extraChapters.toFloat(),
                        valueRange = 0f..100f,
                        steps = 99,
                        enabled = !running,
                        color = Color.Transparent,
                        onValueChange = { extraChapters = it.toInt() },
                    )
                }
            }

            // 场景提示词：这一趟发给模型的整段文本。旧的「AI 分配角色」预设管理页已经退役，
            // 这一条不能再没有入口——它决定识别得准不准，保存之后下一次按「开始」就生效。
            CollapsibleHeader(
                isCollapsed = !promptOpen,
                onToggle = { promptOpen = !promptOpen },
                title = stringResource(R.string.ai_cast_scene_prompt),
                subtitle = stringResource(R.string.ai_cast_scene_prompt_hint),
            )
            if (promptOpen) {
                AiCastSection {
                    val text = promptText
                    if (text == null) {
                        AppText(
                            text = stringResource(R.string.ai_cast_status_loading),
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 单个自由文本框直接用 CastImeTextField：CastFieldStack 是为"多行共用一个
                        // 输入框"设计的，这里只有一行，套上去只会多出一层下拉外壳
                        CastImeTextField(
                            value = text,
                            onValueChange = { promptText = it },
                            singleLine = false,
                            minLines = 6,
                            maxLines = 14,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallOutlinedButton(
                                onClick = {
                                    // 一次点保存 = 一次写库，编辑过程中不落盘
                                    scope.launch(Dispatchers.IO) {
                                        AiCastPresetStore.saveScenePrompt(text)
                                        // toastOnUi 自己回主线程，这里不再切换
                                        context.toastOnUi(R.string.ai_cast_scene_prompt_saved)
                                    }
                                },
                                text = stringResource(R.string.save),
                            )
                            SmallOutlinedButton(
                                // 只把框里的内容换成出厂文本，没点保存就不写库：
                                // 「先看一眼默认长什么样」不该顺手抹掉用户改的那份
                                onClick = { promptText = AiCastPresetStore.defaultScenePrompt() },
                                icon = Icons.Default.Restore,
                                text = stringResource(R.string.ai_cast_scene_prompt_reset),
                            )
                        }
                    }
                }
            }
        }
    }

    // 本章配乐段数：头部那颗「清除本章场景」按它决定能不能按
    LaunchedEffect(chapterIndex) {
        val currentBook = ReadBook.book ?: return@LaunchedEffect
        sceneCount = withContext(Dispatchers.IO) {
            BgmSceneStore.marks(currentBook.bookUrl, chapterIndex).size
        }
    }
}

/**
 * 本次要识别的章区间（**0 基**、含两端）；null = 范围填得不合法（非数字、0、起点在终点之后）。
 *
 * 界面上的章号从 1 开始（与听书下载那张卡片同一口径），这里负责换算并夹到目录末尾；
 * 消费侧是 `castChapterPlan`（配乐场景那条链），它按同一份 0 基口径逐章取正文。
 */
private fun castChapterRange(
    byRange: Boolean,
    startText: String,
    endText: String,
    chapterTotal: Int,
    currentChapter: Int,
    extraChapters: Int,
): Pair<Int, Int>? {
    if (chapterTotal <= 0) return null
    val last = chapterTotal - 1
    if (!byRange) {
        val start = currentChapter.coerceIn(0, last)
        return start to (start + extraChapters).coerceAtMost(last)
    }
    val start = startText.trim().toIntOrNull()?.minus(1) ?: return null
    val end = endText.trim().toIntOrNull()?.minus(1) ?: return null
    if (start < 0 || end < start) return null
    return start.coerceAtMost(last) to end.coerceAtMost(last)
}

/** 分区卡片：标题（可带右侧操作）+ 内容。卡片内边距统一 16.dp，与弹层内容边距同一档。 */
@Composable
private fun AiCastSection(
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    NormalCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        containerColor = LegadoTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = LegadoTheme.typography.titleSmall,
                        color = LegadoTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}
