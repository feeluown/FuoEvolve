package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothLyricsSettingsTest {
    @Test
    fun bluetoothLyricsAreDisabledByDefault() {
        assertFalse(AppSettings().bluetoothLyricsEnabled)
        assertFalse(PersistedSettingsV1().toAppSettings().bluetoothLyricsEnabled)
    }

    @Test
    fun bluetoothLyricsPreferenceRoundTripsThroughPersistence() {
        val persisted = AppSettings(bluetoothLyricsEnabled = true).toPersistedSettings()

        assertTrue(persisted.bluetoothLyricsEnabled == true)
        assertTrue(persisted.toAppSettings().bluetoothLyricsEnabled)
    }
}
