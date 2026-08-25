package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProviderControllerSnapshot(
    val availableProviders: List<ProviderInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val features: List<ProviderFeature> = emptyList(),
    val capabilities: Map<String, ProviderCapabilities> = emptyMap(),
    val authStates: Map<String, ProviderAuthState> = emptyMap(),
    val authOperations: Map<String, ProviderSessionOperation> = emptyMap(),
    val authErrors: Map<String, String> = emptyMap(),
    val recommendSections: List<ProviderContentSection> = emptyList(),
    val musicSections: List<ProviderContentSection> = emptyList(),
    val mineSections: List<ProviderContentSection> = emptyList(),
    val minePlaylistSections: List<ProviderContentSection> = emptyList(),
    val mineFavoritePlaylistSections: List<ProviderContentSection> = emptyList(),
    val lastFailure: ProviderFailure? = null,
)

internal class ProviderControllerState {
    private val mutableState = MutableStateFlow(ProviderControllerSnapshot())
    val state: StateFlow<ProviderControllerSnapshot> = mutableState.asStateFlow()

    var availableProviders: List<ProviderInfo>
        get() = mutableState.value.availableProviders
        set(value) = update { it.copy(availableProviders = value) }

    var providers: List<ProviderInfo>
        get() = mutableState.value.providers
        set(value) = update { it.copy(providers = value) }

    var features: List<ProviderFeature>
        get() = mutableState.value.features
        set(value) = update { it.copy(features = value) }

    var capabilities: Map<String, ProviderCapabilities>
        get() = mutableState.value.capabilities
        set(value) = update { it.copy(capabilities = value) }

    var authStates: Map<String, ProviderAuthState>
        get() = mutableState.value.authStates
        set(value) = update { it.copy(authStates = value) }

    var authOperations: Map<String, ProviderSessionOperation>
        get() = mutableState.value.authOperations
        set(value) = update { it.copy(authOperations = value) }

    var authErrors: Map<String, String>
        get() = mutableState.value.authErrors
        set(value) = update { it.copy(authErrors = value) }

    var recommendSections: List<ProviderContentSection>
        get() = mutableState.value.recommendSections
        set(value) = update { it.copy(recommendSections = value.groupedByContentType()) }

    var musicSections: List<ProviderContentSection>
        get() = mutableState.value.musicSections
        set(value) = update { it.copy(musicSections = value.groupedByContentType()) }

    var mineSections: List<ProviderContentSection>
        get() = mutableState.value.mineSections
        set(value) = update { it.copy(mineSections = value) }

    var minePlaylistSections: List<ProviderContentSection>
        get() = mutableState.value.minePlaylistSections
        set(value) = update { it.copy(minePlaylistSections = value) }

    var mineFavoritePlaylistSections: List<ProviderContentSection>
        get() = mutableState.value.mineFavoritePlaylistSections
        set(value) = update { it.copy(mineFavoritePlaylistSections = value) }

    var lastFailure: ProviderFailure?
        get() = mutableState.value.lastFailure
        set(value) = update { it.copy(lastFailure = value) }

    fun userMessage(throwable: Throwable, fallback: String, providerId: String? = null): String {
        val failure = throwable.providerFailureOrNull(providerId)
        lastFailure = failure
        return failure?.userMessage ?: throwable.message ?: fallback
    }

    private inline fun update(crossinline transform: (ProviderControllerSnapshot) -> ProviderControllerSnapshot) {
        mutableState.update { current -> transform(current) }
    }
}

private fun List<ProviderContentSection>.groupedByContentType(): List<ProviderContentSection> {
    if (size < 2) return this
    val contentTypes = map { it.feature.contentType }.distinct()
    return contentTypes.flatMap { contentType ->
        filter { it.feature.contentType == contentType }
    }
}
