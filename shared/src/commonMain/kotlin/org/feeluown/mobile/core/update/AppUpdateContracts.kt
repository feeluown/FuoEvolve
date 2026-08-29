package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class AppUpdateChannel(val label: String) {
    Stable("Stable"),
    Canary("Canary"),
}

@Serializable
data class AppUpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val apk: AppUpdateApk,
    val commitSha: String? = null,
    val workflowRunId: Long? = null,
    val releaseNotesUrl: String? = null,
)

@Serializable
data class AppUpdateApk(
    val url: String,
    val sha256: String,
    val size: Long,
)

enum class AppUpdateDecision {
    UpToDate,
    UpdateAvailable,
    WaitingForStable,
}

enum class AppUpdatePhase {
    Idle,
    Checking,
    UpToDate,
    UpdateAvailable,
    Downloading,
    InstallPermissionRequired,
    Installing,
    WaitingForStable,
    Error,
}

data class AppUpdateUiState(
    val supported: Boolean = false,
    val channel: AppUpdateChannel = AppUpdateChannel.Stable,
    val autoCheckEnabled: Boolean = true,
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val installedVersionCode: Long = 0,
    val installedVersionName: String = "",
    val remoteVersionCode: Long? = null,
    val remoteVersionName: String? = null,
    val publishedAt: String? = null,
    val releaseNotesUrl: String? = null,
    val downloadProgress: Float? = null,
    val message: String? = null,
)

interface AppUpdateController {
    val uiState: StateFlow<AppUpdateUiState>
    fun setChannel(channel: AppUpdateChannel)
    fun setAutoCheckEnabled(enabled: Boolean)
    fun checkNow()
    fun downloadAndInstall()
}

object NoopAppUpdateController : AppUpdateController {
    private val state = MutableStateFlow(AppUpdateUiState())
    override val uiState: StateFlow<AppUpdateUiState> = state
    override fun setChannel(channel: AppUpdateChannel) = Unit
    override fun setAutoCheckEnabled(enabled: Boolean) = Unit
    override fun checkNow() = Unit
    override fun downloadAndInstall() = Unit
}

internal fun evaluateAppUpdate(
    installedVersionCode: Long,
    channel: AppUpdateChannel,
    remoteVersionCode: Long,
): AppUpdateDecision = when {
    remoteVersionCode > installedVersionCode -> AppUpdateDecision.UpdateAvailable
    channel == AppUpdateChannel.Stable && remoteVersionCode < installedVersionCode -> AppUpdateDecision.WaitingForStable
    else -> AppUpdateDecision.UpToDate
}

internal fun AppUpdateChannel.manifestValue(): String = name.lowercase()
