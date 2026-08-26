package org.feeluown.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

private data class SnackbarFeedbackEvent(
    val message: String,
    val dismiss: () -> Unit,
)

private fun Flow<String?>.toSnackbarFeedbackEvents(
    lifecycle: Lifecycle,
    dismissFeedback: (String) -> Unit,
): Flow<SnackbarFeedbackEvent> = mapNotNull { message ->
    if (message == null) {
        null
    } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        SnackbarFeedbackEvent(message) { dismissFeedback(message) }
    } else {
        dismissFeedback(message)
        null
    }
}

@Composable
internal fun AppFeedbackHost(
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playback = uiGraph.playback
    val snackbarFeedbackEvents = remember(appViewModel, uiGraph, lifecycle) {
        merge(
            playback.playlists.feedback.toSnackbarFeedbackEvents(
                lifecycle,
                playback.playlists::dismissFeedback,
            ),
            playback.queue.feedback.toSnackbarFeedbackEvents(
                lifecycle,
                playback.queue::dismissFeedback,
            ),
            playback.providerTrackActions.feedback.toSnackbarFeedbackEvents(
                lifecycle,
                playback.providerTrackActions::dismissFeedback,
            ),
            playback.downloads.managerState
                .map { it.queueFeedback }
                .distinctUntilChanged()
                .toSnackbarFeedbackEvents(lifecycle, playback.downloads::dismissQueueFeedback),
            playback.sleepTimer.feedback.toSnackbarFeedbackEvents(
                lifecycle,
                playback.sleepTimer::dismissFeedback,
            ),
            appViewModel.appFeedback.toSnackbarFeedbackEvents(lifecycle, appViewModel::dismissFeedback),
            uiGraph.sharedResources.feedback.toSnackbarFeedbackEvents(
                lifecycle,
                uiGraph.sharedResources::dismissFeedback,
            ),
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        snackbar.currentSnackbarData?.dismiss()
    }
    LaunchedEffect(snackbarFeedbackEvents, lifecycle, snackbar) {
        snackbarFeedbackEvents.collect { event ->
            try {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    snackbar.showSnackbar(event.message)
                }
            } finally {
                event.dismiss()
            }
        }
    }

    SnackbarHost(
        hostState = snackbar,
        modifier = modifier,
    ) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}
