package org.feeluown.mobile

/** Platform composition roots inject a dedicated document store, never the settings store. */
fun createPersistentPlaylistMigrationStore(storage: MigrationDocumentStorage): PlaylistMigrationStore =
    JsonPlaylistMigrationStore(object : PlaylistMigrationDocumentStore {
        override suspend fun read(): String? = storage.read()
        override suspend fun write(document: String) = storage.write(document)
    })
