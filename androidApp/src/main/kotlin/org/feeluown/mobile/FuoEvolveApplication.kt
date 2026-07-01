package org.feeluown.mobile

import com.chaquo.python.android.PyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FuoEvolveApplication : PyApplication() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val providerRepository: AndroidFuoCoreBridge by lazy {
        AndroidFuoCoreBridge(applicationContext)
    }

    private val localRepository: AndroidLocalMusicRepository by lazy {
        AndroidLocalMusicRepository(applicationContext)
    }

    private val downloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(applicationContext, providerRepository)
    }

    private val playbackEngine: AndroidNativeAudioEngine by lazy {
        AndroidNativeAudioEngine(applicationContext, appScope)
    }

    private val settingsStore: AndroidAppSettingsStore by lazy {
        AndroidAppSettingsStore(applicationContext)
    }

    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy {
        AndroidResourceCacheRepository(applicationContext)
    }

    val controller: FuoPlayerController by lazy {
        FuoPlayerController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            downloadRepository = downloadRepository,
            playbackEngine = playbackEngine,
            settingsStore = settingsStore,
            resourceCacheRepository = resourceCacheRepository,
            scope = appScope,
        )
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
