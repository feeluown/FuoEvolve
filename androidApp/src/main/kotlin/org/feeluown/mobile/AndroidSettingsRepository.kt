package org.feeluown.mobile

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath

fun createAndroidAppSettingsRepository(
    context: Context,
    scope: CoroutineScope,
): AppSettingsRepository {
    val applicationContext = context.applicationContext
    val dataStore = createSettingsDataStore(
        storage = OkioStorage<Preferences>(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = {
                applicationContext.filesDir.resolve(APP_SETTINGS_FILE_NAME).absolutePath.toPath()
            },
        ),
    )
    return DataStoreAppSettingsRepository(
        dataStore = dataStore,
        legacyLoader = LegacyAppSettingsLoader { AndroidLegacySettingsLoader(applicationContext).load() },
        scope = scope,
    )
}
