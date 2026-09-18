package org.feeluown.mobile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Separate from settings: migration snapshots may contain hundreds of songs. */
const val PLAYLIST_MIGRATION_FILE_NAME = "playlist_migrations.preferences_pb"

interface MigrationDocumentStorage {
    suspend fun read(): String?
    suspend fun write(document: String)
}

class DataStoreMigrationDocumentStorage(
    private val dataStore: DataStore<Preferences>,
) : MigrationDocumentStorage {
    override suspend fun read(): String? = dataStore.data.first()[MIGRATION_DOCUMENT_KEY]

    override suspend fun write(document: String) {
        dataStore.edit { it[MIGRATION_DOCUMENT_KEY] = document }
    }

    private companion object {
        val MIGRATION_DOCUMENT_KEY = stringPreferencesKey("playlist_migrations_json_v1")
    }
}
