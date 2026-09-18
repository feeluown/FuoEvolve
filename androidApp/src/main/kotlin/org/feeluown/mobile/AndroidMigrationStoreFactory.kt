package org.feeluown.mobile

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath

fun createAndroidMigrationDocumentStorage(context: Context): MigrationDocumentStorage {
    val applicationContext = context.applicationContext
    return DataStoreMigrationDocumentStorage(
        createSettingsDataStore(
            storage = OkioStorage<Preferences>(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer,
                producePath = {
                    applicationContext.filesDir.resolve(PLAYLIST_MIGRATION_FILE_NAME).absolutePath.toPath()
                },
            ),
        ),
    )
}
