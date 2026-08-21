package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAudioQualityPolicyTest {
    @Test
    fun savedWifiAndCellularPoliciesAreAppliedTogether() = runTest {
        val settings = AppSettings(
            wifiAudioQualityPolicy = AudioQualityPolicy.Highest,
            cellularAudioQualityPolicy = AudioQualityPolicy.Low,
        )
        var applied: Pair<AudioQualityPolicy, AudioQualityPolicy>? = null

        applySavedAudioQualityPolicies(
            loadSettings = { settings },
            applyPolicies = { wifi, cellular -> applied = wifi to cellular },
        )

        assertEquals(AudioQualityPolicy.Highest to AudioQualityPolicy.Low, applied)
    }
}
