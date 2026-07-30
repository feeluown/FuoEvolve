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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

private data class PendingLocalPlaylistExport(
    val fileName: String,
    val content: String,
)

private data class PendingLocalPlaylistImport(
    val fileName: String,
    val content: String,
)

private const val LOCAL_PLAYLIST_MIME_TYPE = "application/x-fuo"
private const val CONTENT_URI_SCHEME = "content"
private const val FILE_URI_SCHEME = "file"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fuoApplication = application as FuoEvolveApplication
        val launchLocalPlaylistImport = localPlaylistImportFromIntent(intent)
        val launchSharedText = sharedTextFromIntent(intent)

        setContent {
            var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
            var hasMicrophonePermission by remember { mutableStateOf(hasMicrophonePermission()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasAudioPermission = hasAudioPermission()
            }
            val microphonePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                hasMicrophonePermission = hasMicrophonePermission()
            }
            val appViewModel = fuoApplication.appViewModel
            val controller = appViewModel.controller
            val appUiState by appViewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val darkTheme = resolveDarkTheme(appUiState.settings.settings.themeMode, systemDark)
            SideEffect {
                configureSystemBars(darkTheme)
            }
            var pendingWebLoginProviderId by rememberSaveable { mutableStateOf<String?>(null) }
            val webLoginLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val returnedProviderId = result.data
                    ?.getStringExtra(ProviderWebLoginActivity.EXTRA_PROVIDER_ID)
                    .orEmpty()
                val providerId = returnedProviderId.ifBlank { pendingWebLoginProviderId.orEmpty() }
                if (result.resultCode == RESULT_OK) {
                    val cookiesJson = result.data
                        ?.getStringExtra(ProviderWebLoginActivity.EXTRA_COOKIES_JSON)
                        .orEmpty()
                    if (providerId.isNotBlank() && cookiesJson.isNotBlank()) {
                        controller.loginProviderWithCookies(providerId, cookiesJson)
                    }
                }
                pendingWebLoginProviderId = null
            }
            val ytmusicHeaderFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val headerFileJson = runCatching {
                        contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: ""
                    }.getOrDefault("")
                    controller.loginYtmusicWithHeaderFile(headerFileJson)
                }
            }
            var pendingLocalPlaylistExport by remember {
                mutableStateOf<PendingLocalPlaylistExport?>(null)
            }
            val localPlaylistFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val content = runCatching {
                        contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            .orEmpty()
                    }.getOrDefault("")
                    if (content.isBlank()) {
                        controller.showMessage("无法读取本地歌单文件")
                    } else {
                        controller.prepareLocalPlaylistImport(
                            fileName = displayNameFor(uri),
                            content = content,
                        )
                    }
                }
            }
            val localPlaylistExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(LOCAL_PLAYLIST_MIME_TYPE),
            ) { uri ->
                val pending = pendingLocalPlaylistExport
                if (uri != null && pending != null) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(pending.content.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开导出目标")
                    }.onFailure {
                        controller.showMessage(it.message ?: "导出本地歌单失败")
                    }
                }
                pendingLocalPlaylistExport = null
            }

            BackHandler(
                enabled = controller.isFullPlayerOpen ||
                    controller.isVideoFullscreen ||
                    controller.settingsLoginProviderId != null,
            ) {
                controller.navigateBack()
            }

            LaunchedEffect(Unit) {
                launchLocalPlaylistImport?.let(::handleLocalPlaylistImport)
                launchSharedText?.let(controller::openSharedResource)
            }

            AppRoot(
                appViewModel = appViewModel,
                hasAudioPermission = hasAudioPermission,
                appVersionInfo = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onRequestAudioPermission = {
                    permissionLauncher.launch(audioPermissions())
                },
                hasMicrophonePermission = hasMicrophonePermission,
                onRequestMicrophonePermission = {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onOpenProviderWebLogin = { provider ->
                    if (provider.loginConfig != null) {
                        pendingWebLoginProviderId = provider.providerId
                        webLoginLauncher.launch(ProviderWebLoginActivity.createIntent(this@MainActivity, provider))
                    }
                },
                onLogoutProvider = { provider ->
                    ProviderWebLoginActivity.clearWebLoginState()
                    controller.logoutProvider(provider.providerId)
                },
                onImportYtmusicHeaderFile = {
                    ytmusicHeaderFileLauncher.launch(arrayOf("application/json"))
                },
                onImportLocalPlaylistFile = {
                    localPlaylistFileLauncher.launch(
                        arrayOf("text/plain", "application/octet-stream", "application/x-fuo", "*/*"),
                    )
                },
                onExportLocalPlaylistFile = { fileName, content ->
                    pendingLocalPlaylistExport = PendingLocalPlaylistExport(fileName, content)
                    localPlaylistExportLauncher.launch(fileName)
                },
                onShareLocalPlaylistFile = ::shareLocalPlaylistFile,
                onShareText = ::shareText,
            )
        }
    }

    override fun onStop() {
        val controller = (application as FuoEvolveApplication).controller
        controller.onAppBackgrounded()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val localPlaylistImport = localPlaylistImportFromIntent(intent)
        if (localPlaylistImport != null) {
            handleLocalPlaylistImport(localPlaylistImport)
            return
        }
        sharedTextFromIntent(intent)?.let {
            (application as FuoEvolveApplication).controller.openSharedResource(it)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return audioPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun audioPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(sendIntent, "分享"))
    }

    private fun shareLocalPlaylistFile(fileName: String, content: String) {
        val shareDirectory = File(cacheDir, "local-playlists")
        if (!shareDirectory.exists()) shareDirectory.mkdirs()
        val safeName = File(fileName).name.ifBlank { "playlist.fuo" }
        val file = File(shareDirectory, safeName)
        runCatching {
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND)
                .setType(LOCAL_PLAYLIST_MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(sendIntent, "分享本地歌单文件"))
        }.onFailure {
            (application as FuoEvolveApplication).controller.showMessage(it.message ?: "分享本地歌单失败")
        }
    }

    private fun displayNameFor(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
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
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        val supportedMimeType = mimeType == LOCAL_PLAYLIST_MIME_TYPE ||
            mimeType == "application/octet-stream" ||
            mimeType == "text/plain"
        if (!fileName.endsWith(".fuo", ignoreCase = true) && !supportedMimeType) {
            return null
        }
        val content = runCatching {
            contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
        }.getOrDefault("")
        return PendingLocalPlaylistImport(fileName, content)
    }

    private fun handleLocalPlaylistImport(pending: PendingLocalPlaylistImport) {
        val controller = (application as FuoEvolveApplication).controller
        if (pending.content.isBlank()) {
            controller.showMessage("无法读取本地歌单文件")
        } else {
            controller.prepareLocalPlaylistImport(pending.fileName, pending.content)
        }
    }

    private fun configureSystemBars(darkTheme: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private fun resolveDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean {
        return when (themeMode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    }

    private fun sharedTextFromIntent(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
                ?.takeUnless { it.scheme == CONTENT_URI_SCHEME || it.scheme == FILE_URI_SCHEME }
                ?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
