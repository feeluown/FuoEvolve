package org.feeluown.mobile

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

fun createDesktopSettingsSnapshotStore(): SettingsSnapshotStore {
    val fileSystem = FileSystem.SYSTEM
    return DataStoreSettingsSnapshotStore(
        createSettingsDataStore(
            storage = OkioStorage<Preferences>(
                fileSystem = fileSystem,
                serializer = PreferencesSerializer,
                producePath = {
                    val directory = desktopConfigDirectory()
                    fileSystem.createDirectories(directory)
                    directory.resolve(APP_SETTINGS_FILE_NAME)
                },
            ),
        ),
    )
}

private fun desktopConfigDirectory(): Path {
    val home = System.getProperty("user.home").orEmpty()
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val base = when {
        osName.contains("win") -> System.getenv("APPDATA")?.takeIf(String::isNotBlank)
            ?: "$home/AppData/Roaming"
        osName.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
            ?: "$home/.config"
    }
    return "$base/FuoEvolve".toPath()
}
