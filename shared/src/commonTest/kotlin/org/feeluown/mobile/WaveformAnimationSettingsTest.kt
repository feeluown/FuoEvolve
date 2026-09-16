package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaveformAnimationSettingsTest {
    @Test
    fun waveformAnimationIsEnabledByDefault() {
        assertFalse(AppSettings().waveformAnimationDisabled)
        assertFalse(PersistedSettingsV1().toAppSettings().waveformAnimationDisabled)
    }

    @Test
    fun waveformAnimationSettingRoundTripsThroughPersistence() {
        val persisted = AppSettings(waveformAnimationDisabled = true).toPersistedSettings()

        assertTrue(persisted.waveformAnimationDisabled == true)
        assertTrue(persisted.toAppSettings().waveformAnimationDisabled)
    }
}
