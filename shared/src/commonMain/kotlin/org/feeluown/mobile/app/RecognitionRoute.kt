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
    appViewModel: FuoAppViewModel,
    appPort: RecognitionAppPort,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
) {
    val uiState by appViewModel.recognitionUiState.collectAsStateWithLifecycle()
    val detailLoadState by appPort.detailLoadState.collectAsStateWithLifecycle()
    val detailScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(detailLoadState.errorMessage) {
        val message = detailLoadState.errorMessage ?: return@LaunchedEffect
        appPort.clearDetailLoadError()
        snackbarHostState.showSnackbar("资源详情加载失败：$message")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AudioRecognitionFeatureScreen(
            uiState = uiState,
            actions = RecognitionFeatureActions(
                dispatch = appViewModel::dispatchRecognition,
                onBack = appViewModel::closeRecognition,
                onSearchSong = appViewModel::searchRecognizedSong,
                canOpenNeteaseDetail = appPort::canOpenNeteaseDetail,
                onOpenNeteaseDetail = { song ->
                    detailScope.launch { appPort.openNeteaseDetail(song) }
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
