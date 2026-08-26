package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** App-shell composition for the recognition feature. */
@Composable
internal fun RecognitionRoute(
    graph: RecognitionRouteGraph,
    onBack: () -> Unit,
    onSearchSong: (RecognizedSong) -> Unit,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
) {
    val uiState by graph.controller.uiState.collectAsStateWithLifecycle()
    val detailLoadState by graph.appPort.detailLoadState.collectAsStateWithLifecycle()
    val detailScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(detailLoadState.errorMessage) {
        val message = detailLoadState.errorMessage ?: return@LaunchedEffect
        graph.appPort.clearDetailLoadError()
        snackbarHostState.showSnackbar("资源详情加载失败：$message")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AudioRecognitionFeatureScreen(
            uiState = uiState,
            actions = RecognitionFeatureActions(
                dispatch = graph.controller::dispatch,
                onBack = onBack,
                onSearchSong = onSearchSong,
                canOpenNeteaseDetail = graph.appPort::canOpenNeteaseDetail,
                onOpenNeteaseDetail = { song ->
                    detailScope.launch { graph.appPort.openNeteaseDetail(song) }
                },
            ),
            hasMicrophonePermission = hasMicrophonePermission,
            onRequestMicrophonePermission = onRequestMicrophonePermission,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
