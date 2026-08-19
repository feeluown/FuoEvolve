package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class ProviderControllerState {
    var availableProviders by mutableStateOf<List<ProviderInfo>>(emptyList())
    var providers by mutableStateOf<List<ProviderInfo>>(emptyList())
    var features by mutableStateOf<List<ProviderFeature>>(emptyList())
    var capabilities by mutableStateOf<Map<String, ProviderCapabilities>>(emptyMap())
    var authStates by mutableStateOf<Map<String, ProviderAuthState>>(emptyMap())
    var authOperations by mutableStateOf<Map<String, ProviderSessionOperation>>(emptyMap())
    var authErrors by mutableStateOf<Map<String, String>>(emptyMap())

    private var recommendSectionsState by mutableStateOf<List<ProviderContentSection>>(emptyList())
    var recommendSections: List<ProviderContentSection>
        get() = recommendSectionsState
        set(value) {
            recommendSectionsState = value.groupedByContentType()
        }

    private var musicSectionsState by mutableStateOf<List<ProviderContentSection>>(emptyList())
    var musicSections: List<ProviderContentSection>
        get() = musicSectionsState
        set(value) {
            musicSectionsState = value.groupedByContentType()
        }

    var mineSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
    var minePlaylistSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
    var mineFavoritePlaylistSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
    var lastFailure by mutableStateOf<ProviderFailure?>(null)

    fun userMessage(throwable: Throwable, fallback: String, providerId: String? = null): String {
        val failure = throwable.providerFailureOrNull(providerId)
        lastFailure = failure
        return failure?.userMessage ?: throwable.message ?: fallback
    }
}

private fun List<ProviderContentSection>.groupedByContentType(): List<ProviderContentSection> {
    if (size < 2) return this
    val contentTypes = map { it.feature.contentType }.distinct()
    return contentTypes.flatMap { contentType ->
        filter { it.feature.contentType == contentType }
    }
}
