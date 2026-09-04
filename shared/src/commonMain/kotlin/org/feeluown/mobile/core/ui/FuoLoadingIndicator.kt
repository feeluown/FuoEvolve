package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LoadingIndicator as MaterialLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

private const val PageLoadingFadeOutMillis = 100
private const val PageContentFadeInDelayMillis = PageLoadingFadeOutMillis
private const val PageContentFadeInMillis = 160

/** Shared Material Expressive indeterminate loading treatment for content-area loading states. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn(animationSpec = tween(PageLoadingFadeOutMillis)),
        exit = fadeOut(animationSpec = tween(PageLoadingFadeOutMillis)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            MaterialLoadingIndicator()
        }
    }
}

/**
 * Keeps page content composed while an initial load is in progress, but only reveals it after the
 * centered Material Expressive loading indicator has exited. Keeping the content composed avoids
 * resetting list/scroll state and page-side effects when loading completes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PageLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (loading) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (loading) PageLoadingFadeOutMillis else PageContentFadeInMillis,
            delayMillis = if (loading) 0 else PageContentFadeInDelayMillis,
        ),
        label = "page-loading-content-alpha",
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxSize().alpha(contentAlpha),
        ) {
            content()
        }
        AnimatedVisibility(
            visible = loading,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(PageLoadingFadeOutMillis)),
            exit = fadeOut(animationSpec = tween(PageLoadingFadeOutMillis)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                MaterialLoadingIndicator()
            }
        }
    }
}
