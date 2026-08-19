package org.feeluown.mobile

import androidx.compose.runtime.Composable

/** Temporary app-shell bridge for callers that still compose recognition through the legacy facade. */
@Composable
internal fun RecognitionRoute(
    appViewModel: FuoAppViewModel,
    controller: FuoPlayerController,
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit,
) {
    RecognitionRoute(
        appViewModel = appViewModel,
        dependencies = RecognitionRouteDependencies(
            canOpenNeteaseDetail = controller::canOpenRecognizedNeteaseDetail,
            onOpenNeteaseDetail = controller::openRecognizedNeteaseDetail,
        ),
        hasMicrophonePermission = hasMicrophonePermission,
        onRequestMicrophonePermission = onRequestMicrophonePermission,
    )
}
