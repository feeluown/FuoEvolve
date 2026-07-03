package org.feeluown.mobile

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fuoApplication = application as FuoEvolveApplication

        setContent {
            var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasAudioPermission = hasAudioPermission()
            }
            val controller = fuoApplication.controller
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

            BackHandler(enabled = controller.canNavigateBack) {
                controller.navigateBack()
            }

            LaunchedEffect(hasAudioPermission) {
                if (hasAudioPermission) {
                    controller.refreshLocalMusic()
                }
            }

            AppRoot(
                controller = controller,
                hasAudioPermission = hasAudioPermission,
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
            )
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
}
