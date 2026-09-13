@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingFeatureTest {
    @Test
    fun initializesRolesAndReplacementPolicyFromPreferences() = runTest {
        val preferences = FakePreferences(
            initial = providerPreferences(
                enabled = setOf("netease", "bilibili"),
                content = setOf("netease"),
                replacement = setOf("bilibili"),
                policy = "smart",
                score = 0.70,
            ),
        )
        val owner = owner(preferences, this)

        owner.initialize(listOf("netease", "bilibili"))

        assertEquals(setOf("netease", "bilibili"), owner.state.value.selectedProviderIds)
        assertEquals(setOf("netease"), owner.state.value.contentProviderIds)
        assertEquals(setOf("bilibili"), owner.state.value.replacementProviderIds)
        assertTrue(owner.state.value.smartReplacementEnabled)
        assertEquals(0.70, owner.state.value.smartReplacementMinScore)
    }

    @Test
    fun initializationFallsBackToFirstAvailableProviderWithExplicitRoles() = runTest {
        val owner = owner(FakePreferences(providerPreferences(enabled = setOf("removed"))), this)

        owner.initialize(listOf("netease", "qqmusic"))

        assertEquals(setOf("netease"), owner.state.value.selectedProviderIds)
        assertEquals(setOf("netease"), owner.state.value.contentProviderIds)
        assertEquals(setOf("netease"), owner.state.value.replacementProviderIds)
    }

    @Test
    fun newlySelectedBilibiliDefaultsToReplacementOnlyWhenRegularProviderExists() = runTest {
        val owner = owner(FakePreferences(providerPreferences(enabled = setOf("netease"))), this)
        owner.initialize(listOf("netease", "bilibili"))

        owner.setProviderSelected("bilibili", true)

        assertEquals(setOf("netease", "bilibili"), owner.state.value.selectedProviderIds)
        assertEquals(setOf("netease"), owner.state.value.contentProviderIds)
        assertEquals(setOf("netease", "bilibili"), owner.state.value.replacementProviderIds)
    }

    @Test
    fun roleGuardsKeepConfigurationValid() = runTest {
        val owner = owner(FakePreferences(providerPreferences(enabled = setOf("netease"))), this)
        owner.initialize(listOf("netease", "qqmusic"))

        owner.setContentProviderEnabled("netease", false)
        assertEquals(setOf("netease"), owner.state.value.contentProviderIds)
        assertEquals("请至少保留一个常规音源", owner.state.value.feedback?.message)

        owner.setReplacementProviderEnabled("netease", false)
        assertEquals(setOf("netease"), owner.state.value.replacementProviderIds)
        assertEquals("启用智能替换时，请至少保留一个替代音源", owner.state.value.feedback?.message)
    }

    @Test
    fun appliesProviderRolesPolicyAndScoreThenRefreshesCatalog() = runTest {
        val preferences = FakePreferences(providerPreferences(enabled = setOf("netease")))
        val runtime = FakeProviderRuntime()
        val owner = owner(preferences, this, runtime)
        owner.initialize(listOf("netease", "bilibili"))
        owner.setProviderSelected("bilibili", true)
        owner.setReplacementProviderEnabled("netease", false)
        owner.setSmartReplacementMinScore(0.70)
        var success = false

        owner.applyProviderConfiguration(setOf("netease", "bilibili")) { success = it }
        advanceUntilIdle()

        assertTrue(success)
        assertEquals(listOf(setOf("netease", "bilibili")), runtime.updates)
        assertEquals(1, runtime.refreshCount)
        val stored = preferences.providerPreferences.value
        assertEquals(setOf("netease", "bilibili"), stored.enabledProviderIds)
        assertEquals(setOf("netease"), stored.searchProviderIds)
        assertEquals(setOf("netease"), stored.recommendProviderIds)
        assertEquals(setOf("netease"), stored.exploreProviderIds)
        assertEquals(setOf("netease"), stored.mineProviderIds)
        assertEquals(setOf("bilibili"), stored.smartReplacementProviderIds)
        assertEquals("smart", stored.unavailablePlaybackPolicy)
        assertEquals(0.70, stored.smartReplacementMinScore)
        assertFalse(owner.state.value.isBusy)
        assertEquals("音源初始化完成", owner.state.value.feedback?.message)
    }

    @Test
    fun skipPolicyPersistsWithoutDiscardingReplacementSelection() = runTest {
        val preferences = FakePreferences(providerPreferences(enabled = setOf("netease")))
        val owner = owner(preferences, this)
        owner.initialize(listOf("netease"))
        owner.setSmartReplacementEnabled(false)
        var success = false

        owner.applyProviderConfiguration(setOf("netease")) { success = it }
        advanceUntilIdle()

        assertTrue(success)
        val stored = preferences.providerPreferences.value
        assertEquals("skip", stored.unavailablePlaybackPolicy)
        assertEquals(setOf("netease"), stored.smartReplacementProviderIds)
    }

    @Test
    fun rollsBackRuntimeAndPreferencesWhenConfigurationPersistenceFails() = runTest {
        val initial = providerPreferences(enabled = setOf("netease"))
        val preferences = FakePreferences(initial, failNextUpdate = true)
        val runtime = FakeProviderRuntime()
        val owner = owner(preferences, this, runtime)
        owner.initialize(listOf("netease", "qqmusic"))
        owner.setProviderSelected("qqmusic", true)
        var success = true

        owner.applyProviderConfiguration(setOf("netease", "qqmusic")) { success = it }
        advanceUntilIdle()

        assertFalse(success)
        assertEquals(
            listOf(setOf("netease", "qqmusic"), setOf("netease")),
            runtime.updates,
        )
        assertEquals(initial, preferences.providerPreferences.value)
        assertEquals(1, runtime.refreshCount)
        assertEquals("persist failed", owner.state.value.feedback?.message)
        assertEquals(OnboardingFeedbackKind.Error, owner.state.value.feedback?.kind)
    }

    @Test
    fun completionReportsFailureAndCanBeRetried() = runTest {
        val preferences = FakePreferences(
            initial = providerPreferences(enabled = setOf("netease")),
            failCompletion = true,
        )
        val owner = owner(preferences, this)
        var success = true

        owner.complete { success = it }
        advanceUntilIdle()

        assertFalse(success)
        assertFalse(owner.state.value.isBusy)
        assertEquals("complete failed", owner.state.value.feedback?.message)

        preferences.failCompletion = false
        owner.complete { success = it }
        advanceUntilIdle()

        assertTrue(success)
        assertTrue(preferences.completed)
    }

    private fun owner(
        preferences: FakePreferences,
        scope: CoroutineScope,
        runtime: FakeProviderRuntime = FakeProviderRuntime(),
    ) = createOnboardingFeatureOwner(
        preferences = preferences,
        providerRuntime = runtime,
        smartReplacePolicy = "smart",
        skipPolicy = "skip",
        defaultSmartReplacementMinScore = 0.55,
        scope = scope,
    )

    private fun providerPreferences(
        enabled: Set<String>,
        content: Set<String> = emptySet(),
        replacement: Set<String> = emptySet(),
        policy: String = "smart",
        score: Double = 0.55,
    ) = OnboardingProviderPreferences(
        enabledProviderIds = enabled,
        searchProviderIds = content,
        recommendProviderIds = content,
        exploreProviderIds = content,
        mineProviderIds = content,
        smartReplacementProviderIds = replacement,
        unavailablePlaybackPolicy = policy,
        smartReplacementMinScore = score,
    )

    private class FakePreferences(
        initial: OnboardingProviderPreferences<String>,
        private var failNextUpdate: Boolean = false,
        var failCompletion: Boolean = false,
    ) : OnboardingPreferencesPort<String> {
        private val mutablePreferences = MutableStateFlow(initial)
        override val providerPreferences: StateFlow<OnboardingProviderPreferences<String>> = mutablePreferences
        var completed = false

        override suspend fun updateProviderPreferences(value: OnboardingProviderPreferences<String>) {
            if (failNextUpdate) {
                failNextUpdate = false
                throw IllegalStateException("persist failed")
            }
            mutablePreferences.value = value
        }

        override suspend fun markCompleted() {
            if (failCompletion) throw IllegalStateException("complete failed")
            completed = true
        }
    }

    private class FakeProviderRuntime : OnboardingProviderRuntimePort {
        val updates = mutableListOf<Set<String>>()
        var refreshCount = 0

        override suspend fun updateEnabledProviders(providerIds: Set<String>) {
            updates += providerIds
        }

        override fun refreshCatalog() {
            refreshCount += 1
        }
    }
}
