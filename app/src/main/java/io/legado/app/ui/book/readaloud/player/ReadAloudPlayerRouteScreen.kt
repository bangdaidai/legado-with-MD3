package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.theme.ProvideThemeOverride
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
 * 朗读设置是独立的 Navigation 3 整页（`ReadAloudConfigScreen`），这里只发跳页回调；
 * 设置内容用的是全局 [ReadAloudPlayerViewModel] 的设置快照，不依赖阅读器 ViewModel，
 * 所以从胶囊直接进听书页时同样能改朗读设置。
 */
@Composable
fun ReadAloudPlayerRouteScreen(
    onBack: () -> Unit,
    onOpenSettingsPage: () -> Unit,
    onOpenClassicReadAloud: (bookUrl: String) -> Unit = {},
) {
    val playerViewModel: ReadAloudPlayerViewModel = koinInject()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playerTheme = rememberPlayerThemeOverride(playerState)

    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collectLatest { effect ->
            when (effect) {
                is ReadAloudPlayerEffect.ReturnToClassic -> {
                    if (effect.bookUrl.isNotBlank()) {
                        onOpenClassicReadAloud(effect.bookUrl)
                    }
                }

                // 跳页/提示类 effect 属于朗读设置整页宿主（两处收集同一份 flow），
                // 这里必须静默，否则会和设置页各导航一次。
                else -> Unit
            }
        }
    }

    ProvideThemeOverride(playerTheme) {
        ReadAloudPlayerScreenContent(
            state = playerState,
            onIntent = playerViewModel::onIntent,
            onBack = onBack,
            onOpenConfig = onOpenSettingsPage,
        )
    }
}
