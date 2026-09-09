package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMpvRequestCorrelationTest {
    @Test
    fun playlistEntryIdentityAcceptsNormalizedRuntimePath() {
        assertTrue(
            desktopMpvSourceMatchesRequest(
                requestedPath = "https://media.example.test/audio?id=42",
                expectedPlaylistEntryId = 17L,
                currentPlaylistEntryId = 17L,
                currentPath = "https://cdn.example.test/resolved/audio.m4a",
                currentPlaylistFilename = "https://media.example.test/audio?id=42",
            ),
        )
    }

    @Test
    fun playlistFilenameActivatesWhenEntryIdIsTemporarilyUnavailable() {
        assertTrue(
            desktopMpvSourceMatchesRequest(
                requestedPath = "https://media.example.test/audio?id=42",
                expectedPlaylistEntryId = null,
                currentPlaylistEntryId = null,
                currentPath = "https://cdn.example.test/resolved/audio.m4a",
                currentPlaylistFilename = "https://media.example.test/audio?id=42",
            ),
        )
    }

    @Test
    fun playlistEntryMismatchRejectsStaleSameUrlEvent() {
        assertFalse(
            desktopMpvSourceMatchesRequest(
                requestedPath = "https://media.example.test/audio?id=42",
                expectedPlaylistEntryId = 18L,
                currentPlaylistEntryId = 17L,
                currentPath = "https://media.example.test/audio?id=42",
                currentPlaylistFilename = "https://media.example.test/audio?id=42",
            ),
        )
    }

    @Test
    fun unrelatedSourceDoesNotActivatePolling() {
        assertFalse(
            desktopMpvSourceMatchesRequest(
                requestedPath = "https://media.example.test/new.m4a",
                expectedPlaylistEntryId = null,
                currentPlaylistEntryId = null,
                currentPath = "https://media.example.test/old.m4a",
                currentPlaylistFilename = "https://media.example.test/old.m4a",
            ),
        )
    }
}
