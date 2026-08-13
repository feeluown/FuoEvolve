package org.feeluown.mobile

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath

internal class AndroidReplacementLearningStore(context: Context) : ReplacementLearningStore {
    private val applicationContext = context.applicationContext
    private val dataStore = createSettingsDataStore(
        storage = OkioStorage<Preferences>(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = {
                applicationContext.filesDir
                    .resolve(REPLACEMENT_LEARNING_FILE_NAME)
                    .absolutePath
                    .toPath()
            },
        ),
    )

    override suspend fun load(): String? = dataStore.data.first()[STATE_JSON_KEY]

    override suspend fun save(encodedState: String) {
        dataStore.edit { preferences ->
            preferences[STATE_JSON_KEY] = encodedState
        }
    }

    private companion object {
        const val REPLACEMENT_LEARNING_FILE_NAME = "replacement_learning.preferences_pb"
        val STATE_JSON_KEY = stringPreferencesKey("replacement_learning_json_v1")
    }
}
