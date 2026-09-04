package io.legado.app.ui.widget.components.image.cover

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator

/**
 * 书架阅读进度条：已读部分用主题 primary，未读部分用浅色轨道
 */
@Composable
fun BookshelfProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(LegadoTheme.colorScheme.surfaceVariant)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LegadoTheme.colorScheme.primary)
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BookshelfCover(
    name: String?,
    author: String?,
    path: String?,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier.fillMaxWidth(),
    isUpdating: Boolean = false,
    // 阅读进度 0..1，提供时在封面底部显示进度条
    progress: Float? = null,
    badgeText: String? = null,
    showBadgeDot: Boolean = false,
    leftBottomText: String? = null,
    sourceOrigin: String? = null,
    onLoadFinish: (() -> Unit)? = null,
    showLoadingPlaceholder: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedCoverKey: String? = null,
) {
    Box(modifier = modifier) {
        CoilBookCover(
            name = name,
            author = author,
            path = path,
            modifier = coverModifier,
            sourceOrigin = sourceOrigin,
            onLoadFinish = onLoadFinish,
            showLoadingPlaceholder = showLoadingPlaceholder,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedCoverKey = sharedCoverKey,
        )

        // 使用 animatedVisibilityScope 的 animateEnterExit 为叠加层添加同步动画
        val overlayModifier = Modifier.then(
            if (animatedVisibilityScope != null) {
                with(animatedVisibilityScope) {
                    Modifier.animateEnterExit(
                        enter = fadeIn(),
                        exit = fadeOut()
                    )
                }
            } else Modifier
        )

        if (!badgeText.isNullOrEmpty()) {
            TextCard(
                text = badgeText,
                icon = if (showBadgeDot) Icons.Default.Update else null,
                iconSize = 12.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .then(overlayModifier),
                cornerRadius = 4.dp,
                horizontalPadding = 4.dp,
                verticalPadding = 2.dp
            )
        }

        if (!leftBottomText.isNullOrEmpty()) {
            TextCard(
                text = leftBottomText,
                backgroundColor = LegadoTheme.colorScheme.cardContainer,
                contentColor = LegadoTheme.colorScheme.onCardContainer,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .then(overlayModifier),
                cornerRadius = 4.dp,
                horizontalPadding = 4.dp,
                verticalPadding = 2.dp
            )
        }

        if (isUpdating) {
            AppLinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .height(3.dp)
                    .then(overlayModifier)
            )
        } else if (progress != null) {
            BookshelfProgressBar(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 左下角有类型角标时进度条让位到角标上方，避免重叠
                    .padding(bottom = if (leftBottomText.isNullOrEmpty()) 0.dp else 24.dp)
                    .height(3.dp)
                    .then(overlayModifier)
            )
        }
    }
}
