package org.feeluown.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var hasAudioPermission by remember { mutableStateOf(hasAudioPermission()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasAudioPermission = hasAudioPermission()
            }
            val providerRepository = remember { AndroidFuoCoreBridge(applicationContext) }
            val localRepository = remember { AndroidLocalMusicRepository(applicationContext) }
            val downloadRepository = remember {
                AndroidDownloadRepository(applicationContext, providerRepository)
            }
            val playbackEngine = remember {
                AndroidNativeAudioEngine(applicationContext, lifecycleScope)
            }
            val controller = remember {
                FuoPlayerController(
                    providerRepository = providerRepository,
                    localRepository = localRepository,
                    downloadRepository = downloadRepository,
                    playbackEngine = playbackEngine,
                    scope = lifecycleScope,
                )
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
