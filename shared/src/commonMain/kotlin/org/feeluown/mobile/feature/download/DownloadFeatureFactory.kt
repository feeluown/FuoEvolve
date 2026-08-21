package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Composition-root factory for the download owner used by player and download-manager UI. */
fun createDownloadActionPort(
    providerRepository: ProviderMusicRepository,
    downloadRepository: DownloadRepository,
    localRepository: LocalMusicRepository,
    localMusicController: LocalMusicFeatureController,
    settingsRepository: AppSettingsRepository,
    scope: CoroutineScope,
    isLocalMusicSectionActive: () -> Boolean,
    onFeedback: (String) -> Unit = {},
): DownloadActionPort {
    val state = DownloadControllerState()
    val controller = DownloadController(
        providerRepository = providerRepository,
        downloadRepository = downloadRepository,
        localRepository = localRepository,
        localMusicController = localMusicController,
        scope = scope,
        state = state,
        unavailablePlaybackPolicy = {
            settingsRepository.state.value.settings.unavailablePlaybackPolicy
        },
        smartReplacementProviderIds = {
            settingsRepository.state.value.settings.effectiveReplacementProviderIds()
        },
        smartReplacementMinScore = {
            settingsRepository.state.value.settings.smartReplacementMinScore.coerceIn(0.0, 1.0)
        },
        isLocalMusicSectionActive = isLocalMusicSectionActive,
        persistSettings = {
            scope.launch {
                settingsRepository.update { current ->
                    current.copy(downloadParallelism = state.parallelism.coerceIn(1, 5))
                }
            }
        },
        setMessage = onFeedback,
        onError = { throwable ->
            onFeedback(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "下载操作失败" })
        },
    )
    scope.launch {
        val settings = settingsRepository.awaitSettings()
        state.parallelism = settings.downloadParallelism.coerceIn(1, 5)
        downloadRepository.updateParallelism(state.parallelism)
        runCatching { downloadRepository.load() }
        controller.start()
    }
    return controller
}

private fun AppSettings.effectiveReplacementProviderIds(): Set<String> {
    val enabled = enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
    return smartReplacementProviderIds.intersect(enabled).ifEmpty { enabled }
}
