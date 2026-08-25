package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

/** Android/DiLink-only state surfaced through the shared settings UI. */
data class BydVoiceControlSettingsState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val readLogsGranted: Boolean = false,
    val grantCommand: String = "",
)

interface BydVoiceControlSettingsPort {
    val state: StateFlow<BydVoiceControlSettingsState>
    fun setEnabled(enabled: Boolean)
}
