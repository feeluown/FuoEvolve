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
    val store = DataStoreSettingsSnapshotStore(
        createSettingsDataStore(
            storage = OkioStorage<Preferences>(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer,
                producePath = {
                    applicationContext.filesDir.resolve(APP_SETTINGS_FILE_NAME).absolutePath.toPath()
                },
            ),
        ),
    )
    return PersistentAppSettingsRepository(
        store = store,
        legacyLoader = LegacyAppSettingsLoader { AndroidLegacySettingsLoader(applicationContext).load() },
        scope = scope,
    )
}
