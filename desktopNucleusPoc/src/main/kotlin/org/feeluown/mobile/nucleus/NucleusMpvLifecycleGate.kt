package org.feeluown.mobile.nucleus

/**
 * Correlates libmpv lifecycle events with the currently requested playlist entry.
 *
 * FILE_LOADED and PLAYBACK_RESTART do not carry playlist entry ids in libmpv. The JNI backend
 * therefore only forwards them after a START_FILE for the current request has been matched. A
 * polling-based activation may keep playback usable when FILE_LOADED is missed, but it deliberately
 * does not manufacture a matched START_FILE, so a stale queued PLAYBACK_RESTART cannot confirm a
 * newer request.
 */
internal class NucleusMpvLifecycleGate {
    private var matchedStartEntryId: Long? = null
    private var activatedEntryId: Long? = null

    fun reset() {
        matchedStartEntryId = null
        activatedEntryId = null
    }

    fun matchStart(playlistEntryId: Long) {
        matchedStartEntryId = playlistEntryId
        activatedEntryId = null
    }

    fun canAcceptFileLoaded(currentPlaylistEntryId: Long): Boolean =
        matchedStartEntryId == currentPlaylistEntryId

    fun markActivated(currentPlaylistEntryId: Long) {
        activatedEntryId = currentPlaylistEntryId
    }

    fun canAcceptPlaybackRestart(currentPlaylistEntryId: Long): Boolean =
        matchedStartEntryId == currentPlaylistEntryId &&
            activatedEntryId == currentPlaylistEntryId
}
