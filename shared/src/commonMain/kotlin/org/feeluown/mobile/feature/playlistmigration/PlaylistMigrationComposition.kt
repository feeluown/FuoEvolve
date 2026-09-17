package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope

/**
 * Platform composition roots own this controller for the application lifetime. Screen disposal
 * must not cancel migration; a fresh process restores checkpoints but does not auto-resubmit
 * potentially ambiguous writes.
 */
fun createPlaylistMigrationFeatureController(
    storage: MigrationDocumentStorage,
    registry: ProviderRegistryRepository,
    catalog: ProviderCatalogRepository,
    library: ProviderLibraryRepository,
    search: ProviderSearchRepository,
    replacement: PlaybackReplacementProviderPort,
    scope: CoroutineScope,
): PlaylistMigrationFeatureController {
    val adapter = ProviderPlaylistMigrationAdapter(
        catalog = catalog,
        library = library,
        replacement = replacement,
    )
    return PlaylistMigrationFeatureController(
        coordinator = PlaylistMigrationCoordinator(
            store = createPersistentPlaylistMigrationStore(storage),
            provider = adapter,
        ),
        provider = adapter,
        registry = registry,
        search = search,
        scope = scope,
    )
}
