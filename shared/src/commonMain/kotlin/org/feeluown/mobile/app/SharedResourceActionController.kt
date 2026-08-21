package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface SharedResourceActionPort {
    val feedback: StateFlow<String?>
    fun open(text: String)
    fun dismissFeedback(feedback: String)
}

object NoOpSharedResourceActionPort : SharedResourceActionPort {
    override val feedback: StateFlow<String?> = MutableStateFlow(null)
    override fun open(text: String) = Unit
    override fun dismissFeedback(feedback: String) = Unit
}

fun createSharedResourceActionPort(
    providerRepository: ProviderMusicRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    searchController: SearchFeatureController,
    settingsRepository: AppSettingsRepository,
    scope: CoroutineScope,
): SharedResourceActionPort = DefaultSharedResourceActionController(
    providerRepository = providerRepository,
    providerCatalog = providerCatalog,
    providerDetails = providerDetails,
    searchController = searchController,
    settingsRepository = settingsRepository,
    scope = scope,
)

private class DefaultSharedResourceActionController(
    private val providerRepository: ProviderMusicRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val providerDetails: ProviderDetailOwners,
    private val searchController: SearchFeatureController,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : SharedResourceActionPort {
    private val mutableFeedback = MutableStateFlow<String?>(null)
    override val feedback: StateFlow<String?> = mutableFeedback.asStateFlow()

    override fun open(text: String) {
        val resource = parseSharedResource(text)
        if (resource == null) {
            val query = sharedSearchQuery(text)
            if (query.isNullOrBlank()) {
                mutableFeedback.value = "无法识别分享链接"
            } else {
                searchController.searchText(query, providerId = null)
                mutableFeedback.value = "未识别为已支持音源链接，已按分享内容搜索"
            }
            return
        }
        scope.launch {
            runCatching {
                ensureProviderReady(resource.providerId)
                openResource(resource)
            }.onFailure { throwable ->
                mutableFeedback.value = throwable.providerFailureOrNull(resource.providerId)?.userMessage
                    ?: throwable.message
                    ?: "资源加载失败"
            }
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (mutableFeedback.value == feedback) mutableFeedback.value = null
    }

    private suspend fun ensureProviderReady(providerId: String) {
        val catalogState = providerCatalog.uiState.value
        val available = catalogState.availableProviders.ifEmpty {
            providerRepository.initialize()
            providerRepository.availableProviders()
        }
        if (available.none { it.providerId == providerId }) {
            error("未找到 provider：$providerId")
        }

        val settings = settingsRepository.awaitSettings()
        if (providerId in settings.enabledProviderIds) return

        val enabled = settings.enabledProviderIds + providerId
        providerRepository.updateEnabledProviders(enabled)
        settingsRepository.update { current -> current.copy(enabledProviderIds = enabled) }
        providerCatalog.refresh()
    }

    private fun openResource(resource: ShareResourceRef) {
        when (resource.type) {
            ShareResourceType.Song -> providerDetails.track.open(resource.toPlaceholderTrack())
            ShareResourceType.Playlist -> providerDetails.playlist.open(resource.toProviderPlaylist())
            ShareResourceType.Artist,
            ShareResourceType.Album -> providerDetails.mediaItem.open(resource.toProviderMediaItem())
        }
    }

    private fun ShareResourceRef.toPlaceholderTrack(): MusicTrack {
        val trackId = toProviderTrackId()
        return MusicTrack(
            id = trackId,
            title = title,
            artists = artists,
            album = album,
            source = providerId,
            sourceType = TrackSourceType.Provider,
            providerId = trackId,
            providerName = providerName.ifBlank { providerId },
            providerUrl = providerUrl,
        )
    }
}
