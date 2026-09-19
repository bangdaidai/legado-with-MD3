package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.help.IntentHelp
import io.legado.app.ui.book.read.sheet.ReadAloudConfigContent
import io.legado.app.ui.book.read.sheet.asReadBookUiState
import io.legado.app.ui.theme.ProvideThemeOverride
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

/**
 * 听书播放界面（Navigation 3 目的地）。
 *
 * 它是一层普通全屏目的地：整页纵向进出动画完全由 nav3 的
 * `NavDisplay.TransitionKey` / `PopTransitionKey` / `PredictivePopTransitionKey` 提供
 * （见 `MainNavGraph.readAloudPlayerEntryMetadata`）。因此这里不需要 `ModalBottomSheet`
 * 这类窗口级 sheet 容器，也不自己驱动位移，没有「弹层里再开弹层」的
 * shape / 宽度 / 返回键特判；代价是没有下拉关闭手势。
 *
 * 设置卡片用的是全局 [ReadAloudPlayerViewModel] 的设置快照，不依赖阅读器 ViewModel，
 * 所以从胶囊直接进听书页时同样能改朗读设置。
 */
@Composable
fun ReadAloudPlayerRouteScreen(
    showReadAloudConfig: Boolean,
    onReadAloudConfigVisibleChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenTtsEnginesAndVoices: (bookUrl: String?) -> Unit = {},
    onOpenTtsCache: () -> Unit = {},
    onOpenBookVoiceCasting: (bookUrl: String) -> Unit = {},
    onOpenSpeechStoryboard: (bookUrl: String) -> Unit = {},
) {
    val playerViewModel: ReadAloudPlayerViewModel = koinInject()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by playerViewModel.readAloudSettings.collectAsStateWithLifecycle()
    val playerTheme = rememberPlayerThemeOverride(playerState)

    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collectLatest { effect ->
            when (effect) {
                // 设置卡片是 dialog 窗口，不收起会浮在跳过去的整页上面。
                is ReadAloudPlayerEffect.OpenEnginesAndVoices -> {
                    onReadAloudConfigVisibleChange(false)
                    onOpenTtsEnginesAndVoices(effect.bookUrl)
                }

                ReadAloudPlayerEffect.OpenTtsCache -> {
                    onReadAloudConfigVisibleChange(false)
                    onOpenTtsCache()
                }

                is ReadAloudPlayerEffect.OpenBookVoiceCasting -> {
                    onReadAloudConfigVisibleChange(false)
                    onOpenBookVoiceCasting(effect.bookUrl)
                }

                is ReadAloudPlayerEffect.OpenSpeechStoryboard -> {
                    onReadAloudConfigVisibleChange(false)
                    onOpenSpeechStoryboard(effect.bookUrl)
                }

                ReadAloudPlayerEffect.OpenSystemTtsSettings -> IntentHelp.openTTSSetting()

                // 切回阅读器/经典面板依赖阅读器宿主在栈上，跳页导航此前未接通，保持现状。
                ReadAloudPlayerEffect.ReturnToClassic,
                ReadAloudPlayerEffect.ReturnToReaderSettings -> Unit
            }
        }
    }

    ProvideThemeOverride(playerTheme) {
        ReadAloudPlayerScreenContent(
            state = playerState,
            onIntent = playerViewModel::onIntent,
            onBack = onBack,
            onOpenConfig = { onReadAloudConfigVisibleChange(true) },
        )
    }
    // 播放页自己是一层全屏目的地，这里只叠一层设置卡片，全屏只有这一层 scrim。
    AppModalBottomSheet(
        show = showReadAloudConfig,
        onDismissRequest = { onReadAloudConfigVisibleChange(false) },
        title = stringResource(R.string.aloud_config),
    ) {
        ReadAloudConfigContent(
            state = settingsState.asReadBookUiState(),
            playerState = playerState,
            onIntent = playerViewModel::applyReadBookConfigIntent,
            onPlayerIntent = playerViewModel::onIntent,
        )
    }
}
