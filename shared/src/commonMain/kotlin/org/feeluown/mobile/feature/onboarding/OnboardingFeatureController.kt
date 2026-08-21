package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val selectedProviderIds: Set<String> = emptySet(),
    val bilibiliReplacementOnly: Boolean = false,
    val isBusy: Boolean = false,
    val feedback: String? = null,
)

interface OnboardingFeatureController {
    val uiState: StateFlow<OnboardingUiState>
    fun initialize(catalog: ProviderCatalogUiState)
    fun setProviderSelected(providerId: String, selected: Boolean)
    fun setBilibiliReplacementOnly(enabled: Boolean)
    fun applyProviderSelection(onComplete: (Boolean) -> Unit)
    fun complete()
    fun dismissFeedback(feedback: String)
}

fun createOnboardingFeatureController(
    providerRepository: ProviderMusicRepository,
    settingsRepository: AppSettingsRepository,
    providerCatalog: ProviderCatalogFeatureController,
    scope: CoroutineScope,
): OnboardingFeatureController = DefaultOnboardingFeatureController(
    providerRepository = providerRepository,
    settingsRepository = settingsRepository,
    providerCatalog = providerCatalog,
    scope = scope,
)

private class DefaultOnboardingFeatureController(
    private val providerRepository: ProviderMusicRepository,
    private val settingsRepository: AppSettingsRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val scope: CoroutineScope,
) : OnboardingFeatureController {
    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    override val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()
    private var initialized = false

    override fun initialize(catalog: ProviderCatalogUiState) {
        if (initialized || catalog.availableProviders.isEmpty()) return
        initialized = true
        val availableIds = catalog.availableProviders.mapTo(linkedSetOf(), ProviderInfo::providerId)
        val settings = settingsRepository.state.value.settings
        val selected = settings.enabledProviderIds.intersect(availableIds)
            .ifEmpty { setOf(catalog.availableProviders.first().providerId) }
        val replacementOnly = "bilibili" in selected &&
            settings.smartReplacementProviderIds == setOf("bilibili") &&
            listOf(
                settings.searchProviderIds,
                settings.recommendProviderIds,
                settings.exploreProviderIds,
                settings.mineProviderIds,
            ).none { "bilibili" in it }
        mutableUiState.value = mutableUiState.value.copy(
            selectedProviderIds = selected,
            bilibiliReplacementOnly = replacementOnly,
        )
    }

    override fun setProviderSelected(providerId: String, selected: Boolean) {
        val current = uiState.value
        val next = if (selected) current.selectedProviderIds + providerId else current.selectedProviderIds - providerId
        mutableUiState.value = current.copy(
            selectedProviderIds = next,
            bilibiliReplacementOnly = current.bilibiliReplacementOnly && "bilibili" in next,
            feedback = null,
        )
    }

    override fun setBilibiliReplacementOnly(enabled: Boolean) {
        mutableUiState.value = uiState.value.copy(
            bilibiliReplacementOnly = enabled && "bilibili" in uiState.value.selectedProviderIds,
            feedback = null,
        )
    }

    override fun applyProviderSelection(onComplete: (Boolean) -> Unit) {
        val state = uiState.value
        val availableIds = providerCatalog.uiState.value.availableProviders.mapTo(mutableSetOf(), ProviderInfo::providerId)
        val selected = state.selectedProviderIds.intersect(availableIds)
        when {
            selected.isEmpty() -> {
                mutableUiState.value = state.copy(feedback = "请至少选择一个音源")
                onComplete(false)
                return
            }
            state.bilibiliReplacementOnly && selected == setOf("bilibili") -> {
                mutableUiState.value = state.copy(feedback = "Bilibili 仅作为替换音源时，请再选择一个常规音源")
                onComplete(false)
                return
            }
        }
        scope.launch {
            val previous = settingsRepository.state.value.settings
            mutableUiState.value = uiState.value.copy(isBusy = true, feedback = "正在初始化音源")
            val next = onboardingProviderSettings(
                current = previous,
                selectedProviderIds = selected,
                bilibiliReplacementOnly = state.bilibiliReplacementOnly,
            )
            val result = runCatching {
                providerRepository.updateEnabledProviders(selected)
                settingsRepository.update { next }
            }
            if (result.isFailure) {
                runCatching { providerRepository.updateEnabledProviders(previous.enabledProviderIds) }
                runCatching { settingsRepository.update { previous } }
                mutableUiState.value = uiState.value.copy(
                    isBusy = false,
                    feedback = result.exceptionOrNull()?.message ?: "音源初始化失败",
                )
                providerCatalog.refresh()
                onComplete(false)
                return@launch
            }
            providerCatalog.refresh()
            mutableUiState.value = uiState.value.copy(isBusy = false, feedback = "音源初始化完成")
            onComplete(true)
        }
    }

    override fun complete() {
        scope.launch {
            settingsRepository.update { it.copy(onboardingCompleted = true) }
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (uiState.value.feedback == feedback) {
            mutableUiState.value = uiState.value.copy(feedback = null)
        }
    }
}

internal fun onboardingProviderSettings(
    current: AppSettings,
    selectedProviderIds: Set<String>,
    bilibiliReplacementOnly: Boolean,
): AppSettings {
    if (bilibiliReplacementOnly && "bilibili" in selectedProviderIds) {
        val regularProviderIds = selectedProviderIds - "bilibili"
        return current.copy(
            enabledProviderIds = selectedProviderIds,
            searchProviderIds = regularProviderIds,
            recommendProviderIds = regularProviderIds,
            exploreProviderIds = regularProviderIds,
            mineProviderIds = regularProviderIds,
            smartReplacementProviderIds = setOf("bilibili"),
            unavailablePlaybackPolicy = UnavailablePlaybackPolicy.SmartReplace,
        )
    }
    return current.copy(
        enabledProviderIds = selectedProviderIds,
        searchProviderIds = emptySet(),
        recommendProviderIds = emptySet(),
        exploreProviderIds = emptySet(),
        mineProviderIds = emptySet(),
        smartReplacementProviderIds = emptySet(),
    )
}
