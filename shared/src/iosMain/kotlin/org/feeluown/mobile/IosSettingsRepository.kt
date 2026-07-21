package org.feeluown.mobile

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun createIosAppSettingsRepository(scope: CoroutineScope): AppSettingsRepository {
    val dataStore = createSettingsDataStore(
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
    )
    return DataStoreAppSettingsRepository(
        dataStore = dataStore,
        legacyLoader = LegacyAppSettingsLoader { IosLegacySettingsLoader().load() },
        scope = scope,
    )
}
