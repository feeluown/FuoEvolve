package org.feeluown.mobile.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OnboardingFeedbackKind {
    Info,
    Error,
}

data class OnboardingFeedback(
    val message: String,
    val kind: OnboardingFeedbackKind,
)

data class OnboardingFeatureState(
    val selectedProviderIds: Set<String> = emptySet(),
    val contentProviderIds: Set<String> = emptySet(),
    val replacementProviderIds: Set<String> = emptySet(),
    val smartReplacementEnabled: Boolean = true,
    val smartReplacementMinScore: Double = 0.55,
    val isBusy: Boolean = false,
    val feedback: OnboardingFeedback? = null,
)

data class OnboardingProviderPreferences<PlaybackPolicy>(
    val enabledProviderIds: Set<String>,
    val searchProviderIds: Set<String>,
    val recommendProviderIds: Set<String>,
    val exploreProviderIds: Set<String>,
    val mineProviderIds: Set<String>,
    val smartReplacementProviderIds: Set<String>,
    val unavailablePlaybackPolicy: PlaybackPolicy,
    val smartReplacementMinScore: Double,
)

interface OnboardingPreferencesPort<PlaybackPolicy> {
    val providerPreferences: StateFlow<OnboardingProviderPreferences<PlaybackPolicy>>
    suspend fun updateProviderPreferences(value: OnboardingProviderPreferences<PlaybackPolicy>)
    suspend fun markCompleted()
}

interface OnboardingProviderRuntimePort {
    suspend fun updateEnabledProviders(providerIds: Set<String>)
    fun refreshCatalog()
}

interface OnboardingFeatureOwner {
    val state: StateFlow<OnboardingFeatureState>
    fun initialize(availableProviderIds: List<String>)
    fun setProviderSelected(providerId: String, selected: Boolean)
    fun setContentProviderEnabled(providerId: String, enabled: Boolean)
    fun setReplacementProviderEnabled(providerId: String, enabled: Boolean)
    fun setSmartReplacementEnabled(enabled: Boolean)
    fun setSmartReplacementMinScore(value: Double)
    fun applyProviderConfiguration(availableProviderIds: Set<String>, onComplete: (Boolean) -> Unit)
    fun complete(onComplete: (Boolean) -> Unit = {})
    fun dismissFeedback(feedback: OnboardingFeedback)
}

fun <PlaybackPolicy> createOnboardingFeatureOwner(
    preferences: OnboardingPreferencesPort<PlaybackPolicy>,
    providerRuntime: OnboardingProviderRuntimePort,
    smartReplacePolicy: PlaybackPolicy,
    skipPolicy: PlaybackPolicy,
    defaultSmartReplacementMinScore: Double,
    scope: CoroutineScope,
): OnboardingFeatureOwner = DefaultOnboardingFeatureOwner(
    preferences = preferences,
    providerRuntime = providerRuntime,
    smartReplacePolicy = smartReplacePolicy,
    skipPolicy = skipPolicy,
    defaultSmartReplacementMinScore = defaultSmartReplacementMinScore,
    scope = scope,
)

private class DefaultOnboardingFeatureOwner<PlaybackPolicy>(
    private val preferences: OnboardingPreferencesPort<PlaybackPolicy>,
    private val providerRuntime: OnboardingProviderRuntimePort,
    private val smartReplacePolicy: PlaybackPolicy,
    private val skipPolicy: PlaybackPolicy,
    private val defaultSmartReplacementMinScore: Double,
    private val scope: CoroutineScope,
) : OnboardingFeatureOwner {
    private val mutableState = MutableStateFlow(
        OnboardingFeatureState(smartReplacementMinScore = defaultSmartReplacementMinScore),
    )
    override val state: StateFlow<OnboardingFeatureState> = mutableState.asStateFlow()
    private var initialized = false

    override fun initialize(availableProviderIds: List<String>) {
        if (initialized || availableProviderIds.isEmpty()) return
        initialized = true
        val availableIds = availableProviderIds.toSet()
        val current = preferences.providerPreferences.value
        val selected = current.enabledProviderIds.intersect(availableIds)
            .ifEmpty { setOf(availableProviderIds.first()) }
        val configuredContent = listOf(
            current.searchProviderIds,
            current.recommendProviderIds,
            current.exploreProviderIds,
            current.mineProviderIds,
        ).flatten().toSet().intersect(selected)
        val content = configuredContent.ifEmpty { defaultContentProviderIds(selected) }
        val replacements = current.smartReplacementProviderIds.intersect(selected)
            .ifEmpty { selected }
        mutableState.value = mutableState.value.copy(
            selectedProviderIds = selected,
            contentProviderIds = content,
            replacementProviderIds = replacements,
            smartReplacementEnabled = current.unavailablePlaybackPolicy == smartReplacePolicy,
            smartReplacementMinScore = current.smartReplacementMinScore.coerceIn(0.0, 1.0),
            feedback = null,
        )
    }

    override fun setProviderSelected(providerId: String, selected: Boolean) {
        val current = state.value
        if (selected) {
            val nextSelected = current.selectedProviderIds + providerId
            val nextContent = if (providerId == "bilibili" && current.contentProviderIds.isNotEmpty()) {
                current.contentProviderIds
            } else {
                current.contentProviderIds + providerId
            }
            mutableState.value = current.copy(
                selectedProviderIds = nextSelected,
                contentProviderIds = nextContent.ifEmpty { defaultContentProviderIds(nextSelected) },
                replacementProviderIds = current.replacementProviderIds + providerId,
                feedback = null,
            )
            return
        }

        val nextSelected = current.selectedProviderIds - providerId
        var nextContent = current.contentProviderIds - providerId
        var nextReplacement = current.replacementProviderIds - providerId
        if (nextSelected.isNotEmpty() && nextContent.isEmpty()) {
            nextContent = defaultContentProviderIds(nextSelected)
        }
        if (current.smartReplacementEnabled && nextSelected.isNotEmpty() && nextReplacement.isEmpty()) {
            nextReplacement = nextSelected
        }
        mutableState.value = current.copy(
            selectedProviderIds = nextSelected,
            contentProviderIds = nextContent,
            replacementProviderIds = nextReplacement,
            feedback = null,
        )
    }

    override fun setContentProviderEnabled(providerId: String, enabled: Boolean) {
        val current = state.value
        if (providerId !in current.selectedProviderIds) return
        if (!enabled && providerId in current.contentProviderIds && current.contentProviderIds.size <= 1) {
            mutableState.value = current.copy(feedback = errorFeedback("请至少保留一个常规音源"))
            return
        }
        mutableState.value = current.copy(
            contentProviderIds = if (enabled) current.contentProviderIds + providerId else current.contentProviderIds - providerId,
            feedback = null,
        )
    }

    override fun setReplacementProviderEnabled(providerId: String, enabled: Boolean) {
        val current = state.value
        if (providerId !in current.selectedProviderIds) return
        if (
            current.smartReplacementEnabled &&
            !enabled &&
            providerId in current.replacementProviderIds &&
            current.replacementProviderIds.size <= 1
        ) {
            mutableState.value = current.copy(feedback = errorFeedback("启用智能替换时，请至少保留一个替代音源"))
            return
        }
        mutableState.value = current.copy(
            replacementProviderIds = if (enabled) {
                current.replacementProviderIds + providerId
            } else {
                current.replacementProviderIds - providerId
            },
            feedback = null,
        )
    }

    override fun setSmartReplacementEnabled(enabled: Boolean) {
        val current = state.value
        mutableState.value = current.copy(
            smartReplacementEnabled = enabled,
            replacementProviderIds = if (enabled && current.replacementProviderIds.isEmpty()) {
                current.selectedProviderIds
            } else {
                current.replacementProviderIds
            },
            feedback = null,
        )
    }

    override fun setSmartReplacementMinScore(value: Double) {
        mutableState.value = state.value.copy(
            smartReplacementMinScore = value.coerceIn(0.0, 1.0),
            feedback = null,
        )
    }

    override fun applyProviderConfiguration(
        availableProviderIds: Set<String>,
        onComplete: (Boolean) -> Unit,
    ) {
        val currentState = state.value
        val selected = currentState.selectedProviderIds.intersect(availableProviderIds)
        val content = currentState.contentProviderIds.intersect(selected)
        val replacements = currentState.replacementProviderIds.intersect(selected)
        when {
            selected.isEmpty() -> {
                mutableState.value = currentState.copy(feedback = errorFeedback("请至少选择一个音源"))
                onComplete(false)
                return
            }
            content.isEmpty() -> {
                mutableState.value = currentState.copy(feedback = errorFeedback("请至少选择一个常规音源"))
                onComplete(false)
                return
            }
            currentState.smartReplacementEnabled && replacements.isEmpty() -> {
                mutableState.value = currentState.copy(feedback = errorFeedback("启用智能替换时，请至少选择一个替代音源"))
                onComplete(false)
                return
            }
        }

        scope.launch {
            val previous = preferences.providerPreferences.value
            val next = onboardingProviderPreferences(
                current = previous,
                selectedProviderIds = selected,
                contentProviderIds = content,
                replacementProviderIds = replacements,
                smartReplacementEnabled = currentState.smartReplacementEnabled,
                smartReplacementMinScore = currentState.smartReplacementMinScore,
                smartReplacePolicy = smartReplacePolicy,
                skipPolicy = skipPolicy,
            )
            mutableState.value = state.value.copy(isBusy = true, feedback = infoFeedback("正在初始化音源"))
            val result = runCatching {
                providerRuntime.updateEnabledProviders(selected)
                preferences.updateProviderPreferences(next)
            }
            if (result.isFailure) {
                runCatching { providerRuntime.updateEnabledProviders(previous.enabledProviderIds) }
                runCatching { preferences.updateProviderPreferences(previous) }
                providerRuntime.refreshCatalog()
                mutableState.value = state.value.copy(
                    isBusy = false,
                    feedback = errorFeedback(result.exceptionOrNull()?.message ?: "音源初始化失败"),
                )
                onComplete(false)
                return@launch
            }

            providerRuntime.refreshCatalog()
            mutableState.value = state.value.copy(isBusy = false, feedback = infoFeedback("音源初始化完成"))
            onComplete(true)
        }
    }

    override fun complete(onComplete: (Boolean) -> Unit) {
        if (state.value.isBusy) return
        mutableState.value = state.value.copy(isBusy = true, feedback = null)
        scope.launch {
            val result = runCatching { preferences.markCompleted() }
            if (result.isFailure) {
                mutableState.value = state.value.copy(
                    isBusy = false,
                    feedback = errorFeedback(result.exceptionOrNull()?.message ?: "完成初始化失败，请重试"),
                )
                onComplete(false)
                return@launch
            }
            mutableState.value = state.value.copy(isBusy = false)
            onComplete(true)
        }
    }

    override fun dismissFeedback(feedback: OnboardingFeedback) {
        if (state.value.feedback == feedback) {
            mutableState.value = state.value.copy(feedback = null)
        }
    }

    private fun defaultContentProviderIds(selectedProviderIds: Set<String>): Set<String> {
        val regular = selectedProviderIds.filterTo(linkedSetOf()) { it != "bilibili" }
        return regular.ifEmpty { selectedProviderIds.take(1).toSet() }
    }
}

fun <PlaybackPolicy> onboardingProviderPreferences(
    current: OnboardingProviderPreferences<PlaybackPolicy>,
    selectedProviderIds: Set<String>,
    contentProviderIds: Set<String>,
    replacementProviderIds: Set<String>,
    smartReplacementEnabled: Boolean,
    smartReplacementMinScore: Double,
    smartReplacePolicy: PlaybackPolicy,
    skipPolicy: PlaybackPolicy,
): OnboardingProviderPreferences<PlaybackPolicy> = current.copy(
    enabledProviderIds = selectedProviderIds,
    searchProviderIds = contentProviderIds,
    recommendProviderIds = contentProviderIds,
    exploreProviderIds = contentProviderIds,
    mineProviderIds = contentProviderIds,
    smartReplacementProviderIds = replacementProviderIds,
    unavailablePlaybackPolicy = if (smartReplacementEnabled) smartReplacePolicy else skipPolicy,
    smartReplacementMinScore = smartReplacementMinScore.coerceIn(0.0, 1.0),
)

private fun infoFeedback(message: String) = OnboardingFeedback(message, OnboardingFeedbackKind.Info)
private fun errorFeedback(message: String) = OnboardingFeedback(message, OnboardingFeedbackKind.Error)
