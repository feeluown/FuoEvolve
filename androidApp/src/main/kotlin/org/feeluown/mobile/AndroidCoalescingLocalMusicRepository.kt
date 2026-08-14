package org.feeluown.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidCoalescingLocalMusicRepository(
    private val delegate: LocalMusicRepository,
    private val assetStore: AndroidOfflineAssetStore,
) : LocalMusicRepository {
    private val refreshMutex = Mutex()
    private var lastSuccessfulRefreshAt = 0L

    override val mediaChangeEvents: Flow<Unit> = delegate.mediaChangeEvents

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) =
        delegate.updateScanSettings(settings)

    override suspend fun isDatabaseReady(): Boolean = delegate.isDatabaseReady()

    override suspend fun isDatabaseStale(): Boolean {
        if (delegate.isDatabaseStale()) return true
        val assetsByUri = assetStore.all().associateBy { it.localUri }
        if (assetsByUri.isEmpty()) return false
        return delegate.tracks().any { track ->
            val localUri = track.localUri ?: return@any false
            val asset = assetsByUri[localUri] ?: return@any false
            track.sourceType != TrackSourceType.Downloaded ||
                track.id != asset.providerTrackId ||
                track.source != asset.source ||
                track.providerId != asset.providerId
        }
    }

    override suspend fun directories(): List<LocalMusicDirectory> = delegate.directories()

    override suspend fun tracks(): List<MusicTrack> = delegate.tracks()

    override suspend fun refreshDatabase(): List<MusicTrack> = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (lastSuccessfulRefreshAt > 0L &&
            now - lastSuccessfulRefreshAt < LocalLibraryRefreshPolicy.duplicateEventWindowMs &&
            delegate.isDatabaseReady() &&
            !isDatabaseStale()
        ) {
            return@withLock delegate.tracks()
        }
        delegate.refreshDatabase().also {
            lastSuccessfulRefreshAt = System.currentTimeMillis()
        }
    }

    override suspend fun search(keyword: String): List<MusicTrack> = delegate.search(keyword)

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) =
        delegate.updateMetadata(track, metadata)

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) = delegate.saveLyrics(track, lyrics)
}
