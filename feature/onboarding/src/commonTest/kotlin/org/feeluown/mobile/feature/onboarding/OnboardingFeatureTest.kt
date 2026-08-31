@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile.feature.onboarding

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
    fun initializesSelectionAndReplacementOnlyFromNarrowPreferences() = runTest {
        val preferences = FakePreferences(
            initial = providerPreferences(
                enabled = setOf("netease", "bilibili"),
                search = setOf("netease"),
                replacement = setOf("bilibili"),
            ),
        )
        val owner = createOnboardingFeatureOwner(
            preferences = preferences,
            providerRuntime = FakeProviderRuntime(),
            smartReplacePolicy = "smart",
            scope = this,
        )

        owner.initialize(listOf("netease", "bilibili"))

        assertEquals(setOf("netease", "bilibili"), owner.state.value.selectedProviderIds)
        assertTrue(owner.state.value.bilibiliReplacementOnly)
    }

    @Test
    fun initializationFallsBackToFirstAvailableProvider() = runTest {
        val owner = createOnboardingFeatureOwner(
            preferences = FakePreferences(providerPreferences(enabled = setOf("removed"))),
            providerRuntime = FakeProviderRuntime(),
            smartReplacePolicy = "smart",
            scope = this,
        )

        owner.initialize(listOf("netease", "qqmusic"))

        assertEquals(setOf("netease"), owner.state.value.selectedProviderIds)
    }

    @Test
    fun validatesProviderSelectionBeforeMutatingRuntime() = runTest {
        val runtime = FakeProviderRuntime()
        val owner = createOnboardingFeatureOwner(
            preferences = FakePreferences(providerPreferences(enabled = setOf("netease"))),
            providerRuntime = runtime,
            smartReplacePolicy = "smart",
            scope = this,
        )
        owner.initialize(listOf("netease", "bilibili"))
        owner.setProviderSelected("netease", false)
        var success: Boolean? = null

        owner.applyProviderSelection(setOf("netease", "bilibili")) { success = it }

        assertEquals(false, success)
        assertEquals("请至少选择一个音源", owner.state.value.feedback)
        assertTrue(runtime.updates.isEmpty())

        owner.setProviderSelected("bilibili", true)
        owner.setBilibiliReplacementOnly(true)
        owner.applyProviderSelection(setOf("netease", "bilibili")) { success = it }

        assertEquals(false, success)
        assertEquals("Bilibili 仅作为替换音源时，请再选择一个常规音源", owner.state.value.feedback)
        assertTrue(runtime.updates.isEmpty())
    }

    @Test
    fun appliesReplacementOnlyPolicyAndRefreshesCatalog() = runTest {
        val preferences = FakePreferences(providerPreferences(enabled = setOf("netease")))
        val runtime = FakeProviderRuntime()
        val owner = createOnboardingFeatureOwner(
            preferences = preferences,
            providerRuntime = runtime,
            smartReplacePolicy = "smart",
            scope = this,
        )
        owner.initialize(listOf("netease", "bilibili"))
        owner.setProviderSelected("bilibili", true)
        owner.setBilibiliReplacementOnly(true)
        var success = false

        owner.applyProviderSelection(setOf("netease", "bilibili")) { success = it }
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
        assertFalse(owner.state.value.isBusy)
        assertEquals("音源初始化完成", owner.state.value.feedback)
    }

    @Test
    fun rollsBackRuntimeAndPreferencesWhenSelectionPersistenceFails() = runTest {
        val initial = providerPreferences(enabled = setOf("netease"))
        val preferences = FakePreferences(initial, failNextUpdate = true)
        val runtime = FakeProviderRuntime()
        val owner = createOnboardingFeatureOwner(
            preferences = preferences,
            providerRuntime = runtime,
            smartReplacePolicy = "smart",
            scope = this,
        )
        owner.initialize(listOf("netease", "qqmusic"))
        owner.setProviderSelected("qqmusic", true)
        var success = true

        owner.applyProviderSelection(setOf("netease", "qqmusic")) { success = it }
        advanceUntilIdle()

        assertFalse(success)
        assertEquals(
            listOf(setOf("netease", "qqmusic"), setOf("netease")),
            runtime.updates,
        )
        assertEquals(initial, preferences.providerPreferences.value)
        assertEquals(1, runtime.refreshCount)
        assertEquals("persist failed", owner.state.value.feedback)
    }

    @Test
    fun completionUsesNarrowPreferencePort() = runTest {
        val preferences = FakePreferences(providerPreferences(enabled = setOf("netease")))
        val owner = createOnboardingFeatureOwner(
            preferences = preferences,
            providerRuntime = FakeProviderRuntime(),
            smartReplacePolicy = "smart",
            scope = this,
        )

        owner.complete()
        advanceUntilIdle()

        assertTrue(preferences.completed)
    }

    private fun providerPreferences(
        enabled: Set<String>,
        search: Set<String> = emptySet(),
        replacement: Set<String> = emptySet(),
    ) = OnboardingProviderPreferences(
        enabledProviderIds = enabled,
        searchProviderIds = search,
        recommendProviderIds = search,
        exploreProviderIds = search,
        mineProviderIds = search,
        smartReplacementProviderIds = replacement,
        unavailablePlaybackPolicy = "stop",
    )

    private class FakePreferences(
        initial: OnboardingProviderPreferences<String>,
        private var failNextUpdate: Boolean = false,
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
