package org.feeluown.mobile

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/**
 * Android composition root.
 *
 * Keeps platform service wiring and repository construction out of [FuoEvolveApplication],
 * matching the iOS-side container pattern while preserving the existing runtime behavior.
 */
internal class AndroidAppContainer(
    private val application: Application,
) : AutoCloseable {
    private val context: Context = application.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyriconLyricsPublisher: LyriconLyricsPublisher? = null
    private var playbackSessionHolder: PlaybackSession? = null

    val providerRepository: ProviderMusicRepository by lazy {
        createFuoProviderRepository(
            credentials = AndroidProviderCredentialStore(context),
            persistentCache = AndroidProviderCacheStore(context),
            isCellularConnection = ::isCellularConnection,
        )
    }

    private fun isCellularConnection(): Boolean {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private val offlineAssetStore: AndroidOfflineAssetStore by lazy {
        AndroidOfflineAssetStore(context)
    }

    private val indexedLocalRepository: LocalMusicRepository by lazy {
        AndroidIndexedLocalMusicRepository(
            context = context,
            assetStore = offlineAssetStore,
        )
    }

    private val localRepository: LocalMusicRepository by lazy {
        AndroidCoalescingLocalMusicRepository(
            delegate = indexedLocalRepository,
            assetStore = offlineAssetStore,
        )
    }

    private val localPlaylistRepository: AndroidLocalPlaylistRepository by lazy {
        AndroidLocalPlaylistRepository(context)
    }

    private val rawDownloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(context, providerRepository) { tasks ->
            FuoDownloadService.update(context, tasks)
        }
    }

    private val downloadRepository: DownloadRepository by lazy {
        AndroidOfflineAwareDownloadRepository(
            delegate = rawDownloadRepository,
            assetStore = offlineAssetStore,
            scope = appScope,
        )
    }

    private val playbackEngine: AndroidNativeAudioEngine by lazy {
        AndroidNativeAudioEngine(context, appScope)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        createAndroidAppSettingsRepository(context, appScope)
    }

    private val providerSessionRepository: ProviderSessionRepository by lazy {
        DefaultProviderSessionRepository(providerRepository)
    }

    private val navigator by lazy { AppNavigator() }

    private val searchController: SearchFeatureController by lazy {
        val initialSettings = settingsRepository.state.value.settings
        createSearchFeatureController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            scope = appScope,
            providerIdsForSearch = {
                val activeProviderIds = providerSessionRepository.state.value.authStates.keys
                settingsRepository.state.value.settings
                    .searchProviderIdsForFeature()
                    .filter(activeProviderIds::contains)
            },
            providerExists = { providerId ->
                providerId in providerSessionRepository.state.value.authStates
            },
            openSearch = { navigator.navigate(AppRoute.Search) },
            onPreferencesChanged = { searchScope, selectedProviderId ->
                appScope.launch {
                    settingsRepository.update { settings ->
                        settings.copy(
                            searchScope = searchScope,
                            selectedSearchProviderId = selectedProviderId,
                        )
                    }
                }
            },
            initialState = SearchUiState(
                searchScope = initialSettings.searchScope,
                selectedSearchProviderId = initialSettings.selectedSearchProviderId,
            ),
        )
    }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy {
        AndroidPlaybackQueueStore(context)
    }

    private val playbackResumeStore: AndroidPlaybackResumeStore by lazy {
        AndroidPlaybackResumeStore(context)
    }

    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy {
        AndroidResourceCacheRepository(context)
    }

    private val debugLogRepository: AndroidDebugLogRepository by lazy {
        AndroidDebugLogRepository(
            context,
            (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }

    private val audioRecognitionRepository: AndroidAudioRecognitionRepository by lazy {
        AndroidAudioRecognitionRepository(context)
    }

    private val recognitionController: RecognitionFeatureController by lazy {
        createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = appScope,
            isPlaybackActive = { playbackEngine.state.value.status == PlayerStatus.Playing },
            pausePlayback = playbackEngine::pause,
        )
    }

    val controller: FuoPlayerController by lazy {
        FuoPlayerController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            localPlaylistRepository = localPlaylistRepository,
            downloadRepository = downloadRepository,
            playbackEngine = playbackEngine,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
            playbackQueueStore = playbackQueueStore,
            resourceCacheRepository = resourceCacheRepository,
            debugLogRepository = debugLogRepository,
            audioRecognitionRepository = audioRecognitionRepository,
            oauthDeviceCodeAssistant = AndroidOAuthDeviceCodeAssistant(context),
            scope = appScope,
            searchFeatureController = searchController,
            recognitionFeatureController = recognitionController,
        ).also(::wireController)
    }

    private val searchAppPort: SearchAppPort by lazy {
        object : SearchAppPort {
            override val providers: List<ProviderInfo>
                get() = controller.providers
            override val downloadStates: Map<String, DownloadState>
                get() = controller.downloadStates

            override fun closeSearch() = controller.closeSearch()

            override fun playResult(index: Int) = controller.playFromSearch(index)

            override fun addToUpNext(track: MusicTrack) = controller.addToUpNext(track)

            override fun download(track: MusicTrack) = controller.download(track)

            override fun deleteDownload(track: MusicTrack) = controller.deleteDownload(track)

            override fun openArtist(track: MusicTrack) = controller.openTrackArtist(track)

            override fun openAlbum(track: MusicTrack) = controller.openTrackAlbum(track)

            override fun openTrackDetail(track: MusicTrack) = controller.openTrackDetail(track)

            override fun canAddToPlaylist(track: MusicTrack): Boolean =
                controller.canAddTrackToPlaylist(track)

            override fun openPlaylistTargetPicker(track: MusicTrack) =
                controller.openPlaylistTargetPicker(track)

            override fun openMediaItem(item: ProviderMediaItem) = controller.openMediaItem(item)

            override fun openPlaylist(playlist: ProviderPlaylist) {
                controller.openPlaylist(playlist)
            }

            override fun openVideo(video: ProviderVideo) = controller.openVideo(video)
        }
    }

    private val recognitionAppPort: RecognitionAppPort by lazy {
        object : RecognitionAppPort {
            override fun canOpenNeteaseDetail(song: RecognizedSong): Boolean =
                controller.canOpenRecognizedNeteaseDetail(song)

            override fun openNeteaseDetail(song: RecognizedSong) =
                controller.openRecognizedNeteaseDetail(song)
        }
    }

    val appViewModel: FuoAppViewModel by lazy {
        FuoAppViewModel(
            controller = controller,
            searchController = searchController,
            recognitionController = recognitionController,
            searchAppPort = searchAppPort,
            recognitionAppPort = recognitionAppPort,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
        )
    }

    private fun wireController(controller: FuoPlayerController) {
        val playbackSession = createPlaybackSession(controller, appScope)
        playbackSessionHolder = playbackSession

        controller.updateStatusBarLyricsAvailability(isLyriconInstalled(context))
        lyriconLyricsPublisher = LyriconLyricsPublisher(
            context = context,
            playbackSession = playbackSession,
            statusBarLyricsEnabled = { controller.statusBarLyricsEnabled },
            scope = appScope,
        ).also(LyriconLyricsPublisher::start)

        FuoPlaybackService.transportControls = object : FuoPlaybackService.TransportControls {
            override fun toggle() = playbackSession.toggle()

            override fun play() = playbackSession.play()

            override fun pause() = playbackSession.pause()

            override fun previous() = playbackSession.previous()

            override fun next() = playbackSession.next()
        }

        appScope.launch {
            playbackSession.state
                .map { state -> state.currentTrack?.id to state.lyrics }
                .distinctUntilChanged()
                .collect { (trackId, lyrics) ->
                    if (trackId != null) {
                        playbackEngine.publishLockScreenLyrics(trackId, lyrics)
                    }
                }
        }

        appScope.launch {
            playbackSession.state
                .map { state ->
                    Triple(
                        state.currentTrack?.id,
                        state.status,
                        state.queueTrackIds to state.queueIndex,
                    )
                }
                .distinctUntilChanged()
                .collect { (trackId, status, _) ->
                    playbackEngine.republishRestoredState()
                    if (
                        trackId != null &&
                        (status == PlaybackSessionStatus.Playing || status == PlaybackSessionStatus.Paused)
                    ) {
                        playbackQueueStore.flushLatest()
                        playbackResumeStore.flush()
                    }
                }
        }
    }

    override fun close() {
        FuoPlaybackService.transportControls = null
        lyriconLyricsPublisher?.close()
        lyriconLyricsPublisher = null
        playbackSessionHolder = null
        appScope.cancel()
    }
}
