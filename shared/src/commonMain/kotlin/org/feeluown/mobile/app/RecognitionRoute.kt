package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Narrow app-shell dependencies for the recognition feature. */
internal data class RecognitionRouteDependencies(
    val canOpenNeteaseDetail: (RecognizedSong) -> Boolean,
    val onOpenNeteaseDetail: (RecognizedSong) -> Unit,
)

/** App-shell composition for the recognition feature. */
@Composable
internal fun RecognitionRoute(
    appViewModel: FuoAppViewModel,
    dependencies: RecognitionRouteDependencies,
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
            canOpenNeteaseDetail = dependencies.canOpenNeteaseDetail,
            onOpenNeteaseDetail = dependencies.onOpenNeteaseDetail,
        ),
        hasMicrophonePermission = hasMicrophonePermission,
        onRequestMicrophonePermission = onRequestMicrophonePermission,
    )
}
