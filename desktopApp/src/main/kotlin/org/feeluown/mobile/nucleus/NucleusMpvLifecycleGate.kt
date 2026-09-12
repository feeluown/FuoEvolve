package org.feeluown.mobile.nucleus

/**
 * Correlates libmpv lifecycle events with the currently requested playlist entry.
 *
 * FILE_LOADED and PLAYBACK_RESTART do not carry playlist entry ids in libmpv. The JNI backend
 * therefore only forwards them after a START_FILE for the current request has been matched. A
 * polling-based activation may keep playback usable when FILE_LOADED is missed, but it deliberately
 * does not manufacture a matched START_FILE, so a stale queued PLAYBACK_RESTART cannot confirm a
 * newer request.
 *
 * load/stop may reset this gate from the playback caller while libmpv events are dispatched on the
 * backend event thread, so access is synchronized to make request replacement visible atomically.
 */
internal class NucleusMpvLifecycleGate {
    private var matchedStartEntryId: Long? = null
    private var activatedEntryId: Long? = null

    @Synchronized
    fun reset() {
        matchedStartEntryId = null
        activatedEntryId = null
    }

    @Synchronized
    fun matchStart(playlistEntryId: Long) {
        matchedStartEntryId = playlistEntryId
        activatedEntryId = null
    }

    @Synchronized
    fun canAcceptFileLoaded(currentPlaylistEntryId: Long): Boolean =
        matchedStartEntryId == currentPlaylistEntryId

    @Synchronized
    fun markActivated(currentPlaylistEntryId: Long) {
        activatedEntryId = currentPlaylistEntryId
    }

    @Synchronized
    fun canAcceptPlaybackRestart(currentPlaylistEntryId: Long): Boolean =
        matchedStartEntryId == currentPlaylistEntryId &&
            activatedEntryId == currentPlaylistEntryId
}
