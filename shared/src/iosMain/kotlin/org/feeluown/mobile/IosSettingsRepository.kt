package org.feeluown.mobile

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun createIosAppSettingsRepository(scope: CoroutineScope): AppSettingsRepository {
    val store = DataStoreSettingsSnapshotStore(
        createSettingsDataStore(
            storage = OkioStorage<Preferences>(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer,
                producePath = {
                    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
                        directory = NSDocumentDirectory,
                        inDomain = NSUserDomainMask,
                        appropriateForURL = null,
                        create = false,
                        error = null,
                    )
                    (requireNotNull(documentDirectory?.path) + "/$APP_SETTINGS_FILE_NAME").toPath()
                },
            ),
        ),
    )
    return PersistentAppSettingsRepository(
        store = store,
        legacyLoader = LegacyAppSettingsLoader { IosLegacySettingsLoader().load() },
        scope = scope,
    )
}
