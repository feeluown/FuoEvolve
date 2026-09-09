package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothLyricsPublisherTest {
    @Test
    fun currentLineFollowsTimedLyricsAndStaysEmptyBeforeFirstTimestamp() {
        val lyrics = """
            [00:01.000]first line
            [00:03.500]second line
        """.trimIndent()

        assertNull(bluetoothLyricLine(lyrics, 999L))
        assertEquals("first line", bluetoothLyricLine(lyrics, 1_000L))
        assertEquals("first line", bluetoothLyricLine(lyrics, 3_499L))
        assertEquals("second line", bluetoothLyricLine(lyrics, 3_500L))
    }

    @Test
    fun displayUsesLyricAsTitleAndKeepsCanonicalSongIdentityInArtist() {
        assertEquals(
            BluetoothLyricsDisplay(
                title = "current lyric",
                artist = "Song title · Artist name",
            ),
            bluetoothLyricsDisplay(
                trackTitle = "Song title",
                trackArtists = "Artist name",
                lyricLine = " current lyric ",
            ),
        )
    }

    @Test
    fun currentLineUsesPrimaryTextForRichLyrics() {
        val lyrics = composeLyricsWithTranslation(
            main = "[00:00.000]原文",
            translation = "[00:00.000]translation",
        )

        assertEquals("原文", bluetoothLyricLine(lyrics, 0L))
    }

    @Test
    fun bluetoothRouteAcceptsMediaRouterFallbackWhenPrimaryRouteMissesCar() {
        assertTrue(
            resolveBluetoothMediaRouteActive(
                routedBluetooth = false,
                routedDeviceQueryAvailable = true,
                mediaRouterBluetooth = true,
                legacyBluetoothRoute = false,
                connectedBluetoothOutput = false,
                connectedOutputFallbackAllowed = true,
            ),
        )
    }

    @Test
    fun bluetoothRouteAcceptsLegacyActiveRouteFallback() {
        assertTrue(
            resolveBluetoothMediaRouteActive(
                routedBluetooth = false,
                routedDeviceQueryAvailable = true,
                mediaRouterBluetooth = false,
                legacyBluetoothRoute = true,
                connectedBluetoothOutput = false,
                connectedOutputFallbackAllowed = true,
            ),
        )
    }

    @Test
    fun bluetoothRouteUsesConnectedOutputOnlyWhenPrimaryRouteIsUnavailableOnSupportedVersions() {
        assertTrue(
            resolveBluetoothMediaRouteActive(
                routedBluetooth = false,
                routedDeviceQueryAvailable = false,
                mediaRouterBluetooth = false,
                legacyBluetoothRoute = false,
                connectedBluetoothOutput = true,
                connectedOutputFallbackAllowed = true,
            ),
        )
        assertFalse(
            resolveBluetoothMediaRouteActive(
                routedBluetooth = false,
                routedDeviceQueryAvailable = true,
                mediaRouterBluetooth = false,
                legacyBluetoothRoute = false,
                connectedBluetoothOutput = true,
                connectedOutputFallbackAllowed = true,
            ),
        )
    }

    @Test
    fun bluetoothRouteDoesNotTreatConnectedOutputAsActiveOnPreAndroid13() {
        assertFalse(
            resolveBluetoothMediaRouteActive(
                routedBluetooth = false,
                routedDeviceQueryAvailable = false,
                mediaRouterBluetooth = false,
                legacyBluetoothRoute = false,
                connectedBluetoothOutput = true,
                connectedOutputFallbackAllowed = false,
            ),
        )
    }
}
