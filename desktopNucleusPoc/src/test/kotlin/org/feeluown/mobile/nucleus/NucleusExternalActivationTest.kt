package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals

class NucleusExternalActivationTest {
    @Test
    fun coldStartSeedsOnlyPlainFuoFilePaths() {
        assertEquals(
            listOf(
                "/tmp/Fuo Playlist.fuo",
                "C:\\Music\\Road Trip.fuo",
            ),
            nucleusColdStartFileInputs(
                arrayOf(
                    "/tmp/Fuo Playlist.fuo",
                    "C:\\Music\\Road Trip.fuo",
                    "file:///tmp/from-uri.fuo",
                    "fuo://search?q=hello",
                    "--debug",
                ),
            ),
        )
    }

    @Test
    fun secondaryInstanceForwardsFilesAndUrisButNotLauncherFlags() {
        assertEquals(
            listOf(
                "/tmp/list.fuo",
                "file:///tmp/uri-list.fuo",
                "fuo://playlist/123",
                "https://music.163.com/song?id=123",
            ),
            nucleusForwardedExternalInputs(
                arrayOf(
                    "/tmp/list.fuo",
                    "file:///tmp/uri-list.fuo",
                    "fuo://playlist/123",
                    "https://music.163.com/song?id=123",
                    "--started-at-login",
                    "/tmp/readme.txt",
                ),
            ),
        )
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
