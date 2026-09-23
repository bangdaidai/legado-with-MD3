package io.legado.app.ui.book.readaloud.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.help.IntentHelp
import io.legado.app.ui.book.read.ReadBookUiState
import io.legado.app.ui.book.read.sheet.ReadAloudConfigContent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerEffect
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * 朗读设置整页（Navigation 3 目的地）。
 *
 * 原来是阅读器/听书页上的底部弹层；弹层是 dialog 窗口，跳独立整页必须先收起，
 * 层级来回塌陷又恢复，观感乱跳。改成普通页面后栈语义天然正确：
 * 从设置页进「引擎与音色」，返回时设置页原地还在。
 * 设置读写走全局 [io.legado.app.ui.book.readaloud.player.ReadAloudPlayerViewModel]
 * 的设置快照与意图映射，阅读页与听书页两个入口进的是同一页、语义完全一致。
 */
@Composable
fun ReadAloudConfigScreen(
    state: ReadBookUiState,
    playerState: ReadAloudPlayerUiState,
    onIntent: (ReadAloudConfigIntent) -> Unit,
    onPlayerIntent: (ReadAloudPlayerIntent) -> Unit,
    effects: Flow<ReadAloudPlayerEffect>,
    onBack: () -> Unit,
    onOpenEnginesAndVoices: (bookUrl: String?) -> Unit,
    onOpenTtsCache: () -> Unit,
    onOpenBookVoiceCasting: (bookUrl: String) -> Unit,
    onOpenSpeechStoryboard: (bookUrl: String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is ReadAloudPlayerEffect.OpenEnginesAndVoices -> onOpenEnginesAndVoices(effect.bookUrl)
                ReadAloudPlayerEffect.OpenTtsCache -> onOpenTtsCache()
                is ReadAloudPlayerEffect.OpenBookVoiceCasting ->
                    onOpenBookVoiceCasting(effect.bookUrl)

                is ReadAloudPlayerEffect.OpenSpeechStoryboard ->
                    onOpenSpeechStoryboard(effect.bookUrl)

                ReadAloudPlayerEffect.OpenSystemTtsSettings -> IntentHelp.openTTSSetting()
                ReadAloudPlayerEffect.TtsCacheCleared ->
                    context.toastOnUi(context.getString(R.string.clear_cache_success))
                ReadAloudPlayerEffect.SpeechAnalysisAiModelRequired ->
                    context.toastOnUi(context.getString(R.string.speech_analysis_ai_model_required))
                // ReturnToClassic 等其余 effect 属于听书页宿主，本页不处理
                else -> Unit
            }
        }
    }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.aloud_config),
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val contentPadding = adaptiveContentPadding(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        )
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            ReadAloudConfigContent(
                state = state,
                playerState = playerState,
                onIntent = onIntent,
                onPlayerIntent = onPlayerIntent,
            )
        }
    }
}
