package org.feeluown.mobile

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

private data class PendingLocalPlaylistExport(val fileName: String, val content: String)
private data class PendingLocalPlaylistImport(val fileName: String, val content: String)
private const val LOCAL_PLAYLIST_MIME_TYPE = "application/x-fuo"
private const val CONTENT_URI_SCHEME = "content"
private const val FILE_URI_SCHEME = "file"
private const val BYD_INSTRUMENT_PERMISSION_REQUEST_CODE = 4104

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBydInstrumentPermissionsIfNeeded(BYD_INSTRUMENT_PERMISSION_REQUEST_CODE)
        val fuoApplication = application as FuoEvolveApplication
        handleOAuthUserCodeCopyIntent(intent)
        val launchLocalPlaylistImport = localPlaylistImportFromIntent(intent)
        val launchSharedText = sharedTextFromIntent(intent)

        setContent {
            var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
            var hasImagePermission by remember { mutableStateOf(hasImagePermission()) }
            var hasMicrophonePermission by remember { mutableStateOf(hasMicrophonePermission()) }
            val appViewModel = fuoApplication.appViewModel
            remember { AndroidPredictiveBackPreference.initialize(this@MainActivity); Unit }
            val predictiveBackEnabled by AndroidPredictiveBackPreference.enabled

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                hasAudioPermission = hasPermissions(result, audioPermissions())
                hasImagePermission = hasPermissions(result, imagePermissions())
                appViewModel.localMusicFeatureController.onPermissionChange(hasAudioPermission)
            }
            val microphonePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                hasMicrophonePermission = granted
                appViewModel.onMicrophonePermissionChange(granted)
            }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { appViewModel.providerAuthFeatureController.startYtmusicTvOAuthLogin() }

            val lifecycleOwner = LocalLifecycleOwner.current
            val appUiState by appViewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            SideEffect { configureSystemBars(resolveDarkTheme(appUiState.themeMode, systemDark)) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasAudioPermission = hasAudioPermission()
                        hasImagePermission = hasImagePermission()
                        hasMicrophonePermission = hasMicrophonePermission()
                        appViewModel.localMusicFeatureController.onPermissionChange(hasAudioPermission)
                        appViewModel.onMicrophonePermissionChange(hasMicrophonePermission)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            var pendingWebLoginProviderId by rememberSaveable { mutableStateOf<String?>(null) }
            val webLoginLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val providerId = result.data?.getStringExtra(ProviderWebLoginActivity.EXTRA_PROVIDER_ID).orEmpty()
                    .ifBlank { pendingWebLoginProviderId.orEmpty() }
                if (result.resultCode == RESULT_OK) {
                    val cookies = result.data?.getStringExtra(ProviderWebLoginActivity.EXTRA_COOKIES_JSON).orEmpty()
                    if (providerId.isNotBlank() && cookies.isNotBlank()) {
                        appViewModel.providerAuthFeatureController.loginWithCookies(providerId, cookies)
                    }
                }
                pendingWebLoginProviderId = null
            }
            val ytmusicHeaderFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    appViewModel.providerAuthFeatureController.loginYtmusicWithHeaderFile(readText(uri))
                }
            }
            val ytmusicOAuthFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    appViewModel.providerAuthFeatureController.importYtmusicOAuthRelatedJson(readText(uri))
                }
            }

            var pendingLocalPlaylistExport by remember { mutableStateOf<PendingLocalPlaylistExport?>(null) }
            val localPlaylistFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val content = readText(uri)
                    if (content.isBlank()) appViewModel.showFeedback("无法读取本地歌单文件")
                    else appViewModel.localPlaylistFeatureController.prepareImport(displayNameFor(uri), content)
                }
            }
            val localPlaylistExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(LOCAL_PLAYLIST_MIME_TYPE),
            ) { uri ->
                val pending = pendingLocalPlaylistExport
                if (uri != null && pending != null) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { it.write(pending.content.toByteArray(Charsets.UTF_8)) }
                            ?: error("无法打开导出目标")
                    }.onFailure { appViewModel.showFeedback(it.message ?: "导出本地歌单失败") }
                }
                pendingLocalPlaylistExport = null
            }

            val appShellHandlesBack = appViewModel.playbackUiPort.isFullPlayerOpen ||
                appViewModel.playbackNavigationPort.isQueueOpen ||
                appViewModel.providerDetailOwners.video.uiState.value.isFullscreen ||
                appViewModel.playlistActionPort.targetPickerState.value.track != null ||
                appViewModel.providerTrackActionPort.artistTargetPickerState.value.track != null ||
                appViewModel.localMusicFeatureController.uiState.value.metadataEditorTrack != null
            val useLegacyPageBack = AndroidPredictiveBackPreference.isSupported && !predictiveBackEnabled &&
                appUiState.backStack.size > 1 && appUiState.backStack.lastOrNull() != AppRoute.Settings
            BackHandler(enabled = appShellHandlesBack || useLegacyPageBack) {
                appViewModel.dispatch(AppIntent.NavigateBack)
            }

            LaunchedEffect(Unit) {
                launchLocalPlaylistImport?.let(::handleLocalPlaylistImport)
                launchSharedText?.let(appViewModel.sharedResourceActionPort::open)
            }

            AppRoot(
                appViewModel = appViewModel,
                hasAudioPermission = hasAudioPermission,
                hasImagePermission = hasImagePermission,
                appVersionInfo = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onRequestAudioPermission = { permissionLauncher.launch(mediaPermissions()) },
                onRequestImagePermission = { permissionLauncher.launch(imagePermissions()) },
                hasMicrophonePermission = hasMicrophonePermission,
                onRequestMicrophonePermission = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenProviderWebLogin = { provider ->
                    if (provider.loginConfig != null) {
                        pendingWebLoginProviderId = provider.providerId
                        webLoginLauncher.launch(ProviderWebLoginActivity.createIntent(this@MainActivity, provider))
                    }
                },
                onLogoutProvider = { provider ->
                    ProviderWebLoginActivity.clearWebLoginState()
                    appViewModel.providerAuthFeatureController.logout(provider.providerId)
                },
                onImportYtmusicHeaderFile = { ytmusicHeaderFileLauncher.launch(arrayOf("application/json")) },
                onImportYtmusicOAuthFile = { ytmusicOAuthFileLauncher.launch(arrayOf("application/json")) },
                onStartYtmusicOAuth = {
                    val oauthInput = appViewModel.providerAuthFeatureController.oauthInput("ytmusic")
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (oauthInput.clientId.isNotBlank() && oauthInput.clientSecret.isNotBlank() && needsPermission) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        appViewModel.providerAuthFeatureController.startYtmusicTvOAuthLogin()
                    }
                },
                onImportLocalPlaylistFile = {
                    localPlaylistFileLauncher.launch(arrayOf("text/plain", "application/octet-stream", "application/x-fuo", "*/*"))
                },
                onExportLocalPlaylistFile = { fileName, content ->
                    pendingLocalPlaylistExport = PendingLocalPlaylistExport(fileName, content)
                    localPlaylistExportLauncher.launch(fileName)
                },
                onShareLocalPlaylistFile = ::shareLocalPlaylistFile,
                onShareText = ::shareText,
            )

            BackHandler(
                enabled = !appShellHandlesBack && AndroidPredictiveBackPreference.isSupported && !predictiveBackEnabled &&
                    appUiState.backStack.lastOrNull() != AppRoute.Settings,
            ) {
                if (appUiState.backStack.size > 1) appViewModel.dispatch(AppIntent.NavigateBack) else finish()
            }
        }
    }

    override fun onStop() {
        (application as FuoEvolveApplication).appViewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleOAuthUserCodeCopyIntent(intent)) return
        localPlaylistImportFromIntent(intent)?.let { handleLocalPlaylistImport(it); return }
        sharedTextFromIntent(intent)?.let {
            (application as FuoEvolveApplication).appViewModel.sharedResourceActionPort.open(it)
        }
    }

    private fun handleOAuthUserCodeCopyIntent(intent: Intent?): Boolean {
        if (intent?.action != AndroidOAuthDeviceCodeAssistant.ACTION_COPY_OAUTH_USER_CODE) return false
        val code = intent.getStringExtra(AndroidOAuthDeviceCodeAssistant.EXTRA_OAUTH_USER_CODE)?.takeIf { it.isNotBlank() } ?: return true
        AndroidOAuthDeviceCodeAssistant.copyToClipboard(this, code)
        (application as FuoEvolveApplication).appViewModel.showFeedback("验证码已复制：$code")
        intent.action = null
        intent.removeExtra(AndroidOAuthDeviceCodeAssistant.EXTRA_OAUTH_USER_CODE)
        return true
    }

    private fun readText(uri: Uri): String = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    private fun hasAudioPermission() = audioPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun hasImagePermission() = imagePermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun mediaPermissions() = (audioPermissions().toList() + imagePermissions()).distinct().toTypedArray()
    private fun imagePermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun audioPermissions() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasPermissions(result: Map<String, Boolean>, permissions: Array<String>) = permissions.all { permission ->
        result[permission] ?: (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享"))
    }

    private fun shareLocalPlaylistFile(fileName: String, content: String) {
        val dir = File(cacheDir, "local-playlists").also { if (!it.exists()) it.mkdirs() }
        val file = File(dir, File(fileName).name.ifBlank { "playlist.fuo" })
        runCatching {
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).setType(LOCAL_PLAYLIST_MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "分享本地歌单文件"))
        }.onFailure {
            (application as FuoEvolveApplication).appViewModel.showFeedback(it.message ?: "分享本地歌单失败")
        }
    }

    private fun displayNameFor(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "playlist.fuo"
    }

    private fun localPlaylistImportFromIntent(intent: Intent?): PendingLocalPlaylistImport? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != CONTENT_URI_SCHEME && uri.scheme != FILE_URI_SCHEME) return null
        val fileName = displayNameFor(uri)
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val supported = mime == LOCAL_PLAYLIST_MIME_TYPE || mime == "application/octet-stream" || mime == "text/plain"
        if (!fileName.endsWith(".fuo", ignoreCase = true) && !supported) return null
        return PendingLocalPlaylistImport(fileName, readText(uri))
    }

    private fun handleLocalPlaylistImport(pending: PendingLocalPlaylistImport) {
        val vm = (application as FuoEvolveApplication).appViewModel
        if (pending.content.isBlank()) vm.showFeedback("无法读取本地歌单文件")
        else vm.localPlaylistFeatureController.prepareImport(pending.fileName, pending.content)
    }

    private fun configureSystemBars(darkTheme: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private fun resolveDarkTheme(themeMode: ThemeMode, systemDark: Boolean) = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    private fun sharedTextFromIntent(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.takeUnless { it.scheme == CONTENT_URI_SCHEME || it.scheme == FILE_URI_SCHEME }?.toString()
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }?.takeIf { it.isNotBlank() }
}
