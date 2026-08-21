package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Composition-root factory for the Local Music feature owner.
 *
 * Persistence is scoped to Local Music settings only; the feature no longer needs the aggregate
 * player controller to serialize the entire application settings object.
 */
fun createLocalMusicFeatureController(
    repository: LocalMusicRepository,
    providerRepository: ProviderMusicRepository,
    navigator: AppNavigator,
    settingsRepository: AppSettingsRepository,
    providers: () -> List<ProviderInfo>,
    isLocalMusicSectionActive: () -> Boolean,
    scope: CoroutineScope,
    onTrackUpdated: (String, MusicTrack) -> Unit = { _, _ -> },
): LocalMusicFeatureController {
    val state = LocalMusicControllerState()
    val controller = LocalMusicController(
        repository = repository,
        providerRepository = providerRepository,
        navigator = navigator,
        scope = scope,
        state = state,
        providers = providers,
        selectedSearchProviderId = {
            settingsRepository.state.value.settings.selectedSearchProviderId
        },
        isLocalMusicSectionActive = isLocalMusicSectionActive,
        persistSettings = {
            scope.launch {
                settingsRepository.update { current ->
                    current.copy(
                        localMusicViewMode = state.viewMode,
                        excludedLocalMusicDirectoryIds = state.excludedDirectoryIds
                            .mapNotNull(::canonicalLocalMusicDirectoryId)
                            .toSet(),
                        localMusicMinDurationSeconds = state.minDurationSeconds,
                    )
                }
            }
        },
        setLoading = {},
        setMessage = {},
        onError = {},
        onTrackUpdated = onTrackUpdated,
    )
    scope.launch {
        val settings = settingsRepository.awaitSettings()
        controller.restore(
            viewMode = settings.localMusicViewMode,
            excludedDirectoryIds = settings.excludedLocalMusicDirectoryIds,
            minDurationSeconds = settings.localMusicMinDurationSeconds,
        )
        controller.updateScanSettings()
    }
    return controller
}
