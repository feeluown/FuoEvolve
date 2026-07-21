package org.feeluown.mobile

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fuoApplication = application as FuoEvolveApplication
        val launchSharedText = sharedTextFromIntent(intent)

        setContent {
            var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasAudioPermission = hasAudioPermission()
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
                if (providerId.isNotBlank()) {
                    controller.openSettings(providerId)
                }
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

            BackHandler(enabled = controller.canNavigateBack) {
                controller.navigateBack()
            }

            LaunchedEffect(Unit) {
                launchSharedText?.let(controller::openSharedResource)
            }

            AppRoot(
                appViewModel = appViewModel,
                hasAudioPermission = hasAudioPermission,
                appVersionInfo = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onRequestAudioPermission = {
                    permissionLauncher.launch(audioPermissions())
                },
                onOpenProviderWebLogin = { provider ->
                    if (provider.loginConfig != null) {
                        pendingWebLoginProviderId = provider.providerId
                        controller.openSettings(provider.providerId)
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
                onShareText = ::shareText,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    private fun shareText(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(sendIntent, "分享"))
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
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}
