package org.feeluown.mobile

import android.content.pm.ApplicationInfo
import com.chaquo.python.android.PyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FuoEvolveApplication : PyApplication() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val providerRepository: AndroidFuoCoreBridge by lazy {
        AndroidFuoCoreBridge(applicationContext)
    }

    private val localRepository: AndroidLocalMusicRepository by lazy {
        AndroidLocalMusicRepository(applicationContext)
    }

    private val downloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(applicationContext, providerRepository) { tasks ->
            FuoDownloadService.update(applicationContext, tasks)
        }
    }

    private val playbackEngine: AndroidNativeAudioEngine by lazy {
        AndroidNativeAudioEngine(applicationContext, appScope)
    }

    private val settingsStore: AndroidAppSettingsStore by lazy {
        AndroidAppSettingsStore(applicationContext)
    }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy {
        AndroidPlaybackQueueStore(applicationContext)
    }

    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy {
        AndroidResourceCacheRepository(applicationContext)
    }

    private val debugLogRepository: AndroidDebugLogRepository by lazy {
        AndroidDebugLogRepository(applicationContext, (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    val controller: FuoPlayerController by lazy {
        FuoPlayerController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            downloadRepository = downloadRepository,
            playbackEngine = playbackEngine,
            settingsStore = settingsStore,
            playbackQueueStore = playbackQueueStore,
            resourceCacheRepository = resourceCacheRepository,
            debugLogRepository = debugLogRepository,
            scope = appScope,
        ).also { controller ->
            FuoPlaybackService.transportControls = object : FuoPlaybackService.TransportControls {
                override fun toggle() {
                    controller.toggle()
                }

                override fun play() {
                    if (controller.playbackState.status != PlayerStatus.Playing) {
                        controller.toggle()
                    }
                }

                override fun pause() {
                    if (controller.playbackState.status == PlayerStatus.Playing) {
                        controller.toggle()
                    }
                }

                override fun previous() {
                    controller.previous()
                }

                override fun next() {
                    controller.next()
                }
            }
        }
    }

    override fun onTerminate() {
        FuoPlaybackService.transportControls = null
        appScope.cancel()
        super.onTerminate()
    }
}
