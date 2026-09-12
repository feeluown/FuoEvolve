package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals

class NucleusExternalActivationTest {
    @Test
    fun coldStartSeedsPlainFuoFilesAndMediaActions() {
        assertEquals(
            listOf(
                "/tmp/Fuo Playlist.fuo",
                "C:\\Music\\Road Trip.fuo",
                DESKTOP_MEDIA_PLAY_PAUSE_ARGUMENT,
            ),
            nucleusColdStartFileInputs(
                arrayOf(
                    "/tmp/Fuo Playlist.fuo",
                    "C:\\Music\\Road Trip.fuo",
                    "file:///tmp/from-uri.fuo",
                    "fuo://search?q=hello",
                    DESKTOP_MEDIA_PLAY_PAUSE_ARGUMENT,
                    "--debug",
                ),
            ),
        )
    }

    @Test
    fun secondaryInstanceForwardsFilesUrisAndMediaActionsButNotLauncherFlags() {
        assertEquals(
            listOf(
                "/tmp/list.fuo",
                "file:///tmp/uri-list.fuo",
                "fuo://playlist/123",
                "https://music.163.com/song?id=123",
                DESKTOP_MEDIA_PREVIOUS_ARGUMENT,
                DESKTOP_MEDIA_NEXT_ARGUMENT,
            ),
            nucleusForwardedExternalInputs(
                arrayOf(
                    "/tmp/list.fuo",
                    "file:///tmp/uri-list.fuo",
                    "fuo://playlist/123",
                    "https://music.163.com/song?id=123",
                    DESKTOP_MEDIA_PREVIOUS_ARGUMENT,
                    DESKTOP_MEDIA_NEXT_ARGUMENT,
                    "--started-at-login",
                    "/tmp/readme.txt",
                ),
            ),
        )
    }

    @Test
    fun mediaActionArgumentsMapToPlaybackActions() {
        assertEquals(NucleusDesktopMediaAction.PlayPause, nucleusDesktopMediaAction(DESKTOP_MEDIA_PLAY_PAUSE_ARGUMENT))
        assertEquals(NucleusDesktopMediaAction.Previous, nucleusDesktopMediaAction(DESKTOP_MEDIA_PREVIOUS_ARGUMENT))
        assertEquals(NucleusDesktopMediaAction.Next, nucleusDesktopMediaAction(DESKTOP_MEDIA_NEXT_ARGUMENT))
        assertEquals(null, nucleusDesktopMediaAction("--debug"))
    }

    @Test
    fun restorePayloadRoundTripsArgumentsWithSpaces() {
        val args = arrayOf(
            "C:\\Users\\Bruce\\Music\\My Playlist.fuo",
            "fuo://search?q=hello%20world",
        )
        assertEquals(
            args.toList(),
            decodeNucleusActivationArguments(encodeNucleusActivationArguments(args)),
        )
    }
}
