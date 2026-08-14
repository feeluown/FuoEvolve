package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class DownloadControllerState {
    var states by mutableStateOf<Map<String, DownloadState>>(emptyMap())
    var tasks by mutableStateOf<List<DownloadTask>>(emptyList())
    var queueFeedback by mutableStateOf<String?>(null)
    var parallelism by mutableStateOf(DEFAULT_DOWNLOAD_PARALLELISM)
}
