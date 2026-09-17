package org.feeluown.mobile

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath

fun createDesktopMigrationDocumentStorage(): MigrationDocumentStorage {
    val fileSystem = FileSystem.SYSTEM
    return DataStoreMigrationDocumentStorage(
        createSettingsDataStore(
            storage = OkioStorage<Preferences>(
                fileSystem = fileSystem,
                serializer = PreferencesSerializer,
                producePath = {
                    val directory = requireNotNull(desktopSettingsFilePath().toPath().parent)
                    fileSystem.createDirectories(directory)
                    directory.resolve(PLAYLIST_MIGRATION_FILE_NAME)
                },
            ),
        ),
    )
}
