package io.legado.app.ui.book.readaloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.core.ui.player.PlayerMorphAppearance
import io.legado.app.core.ui.player.PlayerMorphHost
import io.legado.app.ui.book.read.sheet.ReadAloudConfigContent
import io.legado.app.ui.book.read.sheet.asReadBookUiState
import io.legado.app.ui.book.readaloud.morph.ReadAloudMorphState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerEffect
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerScreenContent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerViewModel
import io.legado.app.ui.book.readaloud.player.applyReadAloudConfigIntent
import io.legado.app.ui.book.readaloud.player.rememberPlayerThemeOverride
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

/** 朗读特有的设置与经典控制；几何、封面和返回手势由共用宿主处理。 */
@Composable
fun ReadAloudPlayerMorphHost(
    playerViewModel: ReadAloudPlayerViewModel,
    playerState: ReadAloudPlayerUiState,
    morph: ReadAloudMorphState,
    visible: Boolean,
    awaitCapsuleAnchor: Boolean = false,
    predictiveBackEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onSwitchToClassic: (bookUrl: String) -> Unit,
    /** 设置弹窗里的跳页/提示意图；导航与 toast 由宿主（MainActivity）统一处理。 */
    onSettingsEffect: (ReadAloudPlayerEffect) -> Unit = {},
) {
    val settingsState by playerViewModel.readAloudSettings.collectAsStateWithLifecycle()
    var configVisible by rememberSaveable { mutableStateOf(false) }
    val expanded by remember { derivedStateOf { morph.expanded } }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentSwitch by rememberUpdatedState(onSwitchToClassic)
    val currentOnSettingsEffect by rememberUpdatedState(onSettingsEffect)
    LaunchedEffect(expanded) { if (!expanded) configVisible = false }

    // 浮层每次拉起都重拍书目快照。没点过播放时朗读状态不翻转、换书/换章也不发那四类事件，
    // 而胶囊叠层让这个单例 ViewModel 的订阅从不中断——不主动刷新，浮层就会停在
    // 上一本（甚至上上一本）书的正文、封面和进度上。
    LaunchedEffect(visible) {
        if (visible) playerViewModel.onIntent(ReadAloudPlayerIntent.Refresh)
    }

    // 浮层收起（回阅读界面）这一刻请求「页面追声音」：浮层 ViewModel 是 Koin 单例不会
    // onCleared，morph 展开态的下降沿是唯一可靠的「离开听书」信号。
    var wasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) {
            playerViewModel.onIntent(ReadAloudPlayerIntent.LeavingToReader)
        }
        wasExpanded = expanded
    }

    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collect { effect ->
            when (effect) {
                is ReadAloudPlayerEffect.ReturnToClassic -> {
                    configVisible = false
                    morph.animateTo(0f)
                    currentDismiss()
                    if (effect.bookUrl.isNotBlank()) {
                        currentSwitch(effect.bookUrl)
                    }
                }

                // 跳页与提示类意图统一交给宿主：导航进 nav3 目的地或 toast。
                is ReadAloudPlayerEffect.OpenEnginesAndVoices,
                is ReadAloudPlayerEffect.OpenTtsCache,
                is ReadAloudPlayerEffect.OpenBookVoiceCasting,
                is ReadAloudPlayerEffect.OpenSpeechStoryboard,
                is ReadAloudPlayerEffect.OpenSystemTtsSettings,
                is ReadAloudPlayerEffect.TtsCacheCleared,
                is ReadAloudPlayerEffect.SpeechAnalysisAiModelRequired,
                -> {
                    configVisible = false
                    currentOnSettingsEffect(effect)
                }

                else -> Unit
            }
        }
    }
    PlayerMorphHost(
        appearance = PlayerMorphAppearance(
            playerState.bookName, playerState.author, playerState.coverPath,
            playerState.sourceOrigin, playerState.bgMode,
        ),
        playerTheme = rememberPlayerThemeOverride(playerState),
        morph = morph,
        visible = visible,
        awaitCapsuleAnchor = awaitCapsuleAnchor,
        predictiveBackEnabled = predictiveBackEnabled,
        backEnabled = !configVisible && playerState.activeSheet == null,
        onDismiss = onDismiss,
    ) { onCollapse ->
        ReadAloudPlayerScreenContent(
            state = playerState,
            onIntent = playerViewModel::onIntent,
            onBack = onCollapse,
            onOpenConfig = { configVisible = true },
        )
    }
    AppModalBottomSheet(
        show = expanded && configVisible,
        onDismissRequest = { configVisible = false },
        title = stringResource(R.string.aloud_config),
    ) {
        ReadAloudConfigContent(
            state = settingsState.asReadBookUiState(),
            playerState = playerState,
            onIntent = playerViewModel::applyReadAloudConfigIntent,
            onPlayerIntent = playerViewModel::onIntent,
        )
    }
}
