package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** App-shell composition for the recognition feature. */
@Composable
internal fun RecognitionRoute(
    appViewModel: FuoAppViewModel,
    appPort: RecognitionAppPort,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
) {
    val uiState by appViewModel.recognitionUiState.collectAsStateWithLifecycle()

    AudioRecognitionFeatureScreen(
        uiState = uiState,
        actions = RecognitionFeatureActions(
            dispatch = appViewModel::dispatchRecognition,
            onBack = appViewModel::closeRecognition,
            onSearchSong = appViewModel::searchRecognizedSong,
            canOpenNeteaseDetail = appPort::canOpenNeteaseDetail,
            onOpenNeteaseDetail = appPort::openNeteaseDetail,
        ),
        hasMicrophonePermission = hasMicrophonePermission,
        onRequestMicrophonePermission = onRequestMicrophonePermission,
    )
}
