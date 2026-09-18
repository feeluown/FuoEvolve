package org.feeluown.mobile

/** Platform capabilities consumed by the common app shell. */
data class AppPlatformBindings(
    val hasAudioPermission: Boolean,
    val onRequestAudioPermission: () -> Unit,
    val audioRecognitionAccess: AudioRecognitionAccess,
    val onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    val onLogoutProvider: (ProviderInfo) -> Unit,
    val onImportYtmusicHeaderFile: (() -> Unit)? = null,
    val onImportYtmusicOAuthFile: (() -> Unit)? = null,
    val onStartYtmusicOAuth: (() -> Unit)? = null,
    val onImportLocalPlaylistFile: (() -> Unit)? = null,
    val onExportLocalPlaylistFile: ((String, String) -> Unit)? = null,
    val onShareLocalPlaylistFile: ((String, String) -> Unit)? = null,
    val onShareText: (String) -> Unit = {},
    val appVersionInfo: String? = null,
    val hasImagePermission: Boolean = true,
    val onRequestImagePermission: () -> Unit = {},
    /** Called from an explicit migration action before an OS-visible long task is scheduled. */
    val onPreparePlaylistMigrationBackground: () -> Unit = {},
    val desktopVideoSettingsAvailable: Boolean = false,
)
