package org.feeluown.mobile

class IosLocalMusicRepository : LocalMusicRepository {
    private var scanSettings = LocalMusicScanSettings()

    val hasPermission: Boolean = true

    fun requestPermission(onGranted: () -> Unit) {
        onGranted()
    }

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
    }

    override suspend fun directories(): List<LocalMusicDirectory> = emptyList()

    override suspend fun scan(): List<MusicTrack> = emptyList()

    override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
}
