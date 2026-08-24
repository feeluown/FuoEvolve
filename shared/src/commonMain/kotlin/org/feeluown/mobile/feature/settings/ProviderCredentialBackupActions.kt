package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

/** Platform capability surfaced inside the shared settings UI. */
data class ProviderCredentialBackupActions(
    val exportAll: (() -> Unit)? = null,
    val exportProvider: ((ProviderInfo) -> Unit)? = null,
    val importBackup: (() -> Unit)? = null,
) {
    val isAvailable: Boolean
        get() = exportAll != null && exportProvider != null && importBackup != null
}

val LocalProviderCredentialBackupActions = staticCompositionLocalOf { ProviderCredentialBackupActions() }
