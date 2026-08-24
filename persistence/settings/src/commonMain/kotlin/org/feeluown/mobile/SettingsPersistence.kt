package org.feeluown.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val APP_SETTINGS_FILE_NAME = "app_settings.preferences_pb"

@Serializable
data class PersistedSmartReplacementSelection(
    val replacementId: String,
    val replacementTitle: String,
    val replacementArtists: String,
    val replacementAlbum: String = "",
    val replacementSource: String,
    val replacementProviderName: String? = null,
    val replacementCoverUrl: String? = null,
    val replacementDurationMs: Long? = null,
    val replacementScore: Double = 0.0,
)

@Serializable
data class PersistedPlaylistPlaybackStat(
    val playCount: Long = 0,
    val lastPlayedAtMillis: Long = 0,
)

/**
 * Stable persistence schema for application settings.
 *
 * Enum-like values are stored as names instead of depending on feature or UI
 * modules. Nullable fields distinguish an absent value from the current app
 * default, allowing the application read model to evolve independently while
 * preserving compatibility with the historical app_settings_json_v1 payload.
 * Provider login drafts are intentionally not represented here.
 */
@Serializable
data class PersistedSettingsV1(
    val onboardingCompleted: Boolean? = null,
    val homeSection: String? = null,
    val mineSection: String? = null,
    val playlistFilter: String? = null,
    val localMusicViewMode: String? = null,
    val excludedLocalMusicDirectoryIds: Set<String>? = null,
    val localMusicMinDurationSeconds: Int? = null,
    val searchScope: String? = null,
    val selectedSearchProviderId: String? = null,
    val selectedSettingsProviderId: String? = null,
    val providerLoginMode: String? = null,
    val enabledProviderIds: Set<String>? = null,
    val providerOrderIds: List<String>? = null,
    val searchProviderIds: Set<String>? = null,
    val recommendProviderIds: Set<String>? = null,
    val exploreProviderIds: Set<String>? = null,
    val mineProviderIds: Set<String>? = null,
    val audioCacheLimitMb: Int? = null,
    val imageCacheLimitMb: Int? = null,
    val downloadParallelism: Int? = null,
    val wifiAudioQualityPolicy: String? = null,
    val cellularAudioQualityPolicy: String? = null,
    val unavailablePlaybackPolicy: String? = null,
    val smartReplacementProviderIds: Set<String>? = null,
    val smartReplacementMinScore: Double? = null,
    val smartReplacementSelections: Map<String, PersistedSmartReplacementSelection>? = null,
    val lyricsAssociations: Map<String, String>? = null,
    val pauseOnOtherAppPlayback: Boolean? = null,
    val lyricFontSize: String? = null,
    val statusBarLyricsEnabled: Boolean? = null,
    val bydInstrumentLyricsEnabled: Boolean? = null,
    val themeMode: String? = null,
    val themeColorScheme: String? = null,
    val themePaletteStyle: String? = null,
    val themeColorSpec: String? = null,
    val dynamicCoverColorEnabled: Boolean? = null,
    val playlistPlaybackStatsVersion: Int? = null,
    val playlistPlaybackStats: Map<String, PersistedPlaylistPlaybackStat>? = null,
)

sealed interface SettingsSnapshotReadResult {
    data object Missing : SettingsSnapshotReadResult
    data object Corrupted : SettingsSnapshotReadResult
    data class Loaded(val snapshot: PersistedSettingsV1) : SettingsSnapshotReadResult
}

interface SettingsSnapshotStore {
    suspend fun read(): SettingsSnapshotReadResult
    suspend fun write(snapshot: PersistedSettingsV1)
}

fun createSettingsDataStore(storage: Storage<Preferences>): DataStore<Preferences> =
    DataStoreFactory.create(storage = storage)

class DataStoreSettingsSnapshotStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsSnapshotStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun read(): SettingsSnapshotReadResult {
        val raw = dataStore.data.first()[SETTINGS_JSON_KEY]
        if (raw.isNullOrBlank()) return SettingsSnapshotReadResult.Missing
        return runCatching { json.decodeFromString<PersistedSettingsV1>(raw) }
            .fold(
                onSuccess = { SettingsSnapshotReadResult.Loaded(it) },
                onFailure = { SettingsSnapshotReadResult.Corrupted },
            )
    }

    override suspend fun write(snapshot: PersistedSettingsV1) {
        val raw = json.encodeToString(snapshot)
        dataStore.edit { preferences -> preferences[SETTINGS_JSON_KEY] = raw }
    }

    companion object {
        const val SETTINGS_JSON_KEY_NAME = "app_settings_json_v1"
        val SETTINGS_JSON_KEY = stringPreferencesKey(SETTINGS_JSON_KEY_NAME)
    }
}
