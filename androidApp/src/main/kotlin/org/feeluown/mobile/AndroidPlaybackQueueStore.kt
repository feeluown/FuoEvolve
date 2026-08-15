package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlaybackQueueStore(context: Context) : PlaybackQueueStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val playbackResumeStore = AndroidPlaybackResumeStore(applicationContext)

    @Volatile
    private var latestSnapshot: PlaybackQueueSnapshot? = null

    @Volatile
    private var loadCompleted: Boolean = false

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        val persistedSnapshot = preferences.getString(KEY_QUEUE_SNAPSHOT, null)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
        val restoredSession = playbackResumeStore.load()
        val snapshot = persistedSnapshot.reconcileRestoredSession(restoredSession)
        latestSnapshot = snapshot
        loadCompleted = true
        snapshot
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        // The Android playback engine can publish its restored session before the controller has
        // loaded the durable queue. Ignore those bootstrap writes: otherwise an empty/default
        // controller queue can overwrite the real queue (including shuffle/repeat) during startup.
        if (!loadCompleted) return

        // FuoPlayerController launches save from its main-immediate scope. Capture the complete
        // snapshot before the first suspension so a later pause can synchronously flush exactly
        // this queue even if the asynchronous IO write has not finished yet.
        latestSnapshot = snapshot
        withContext(Dispatchers.IO) {
            writeSnapshot(snapshot)
        }
    }

    /**
     * Durably writes the most recent complete controller queue snapshot. This is intentionally
     * synchronous and is only used at important playback lifecycle boundaries (playing/paused),
     * where surviving an abrupt process kill is more important than deferring a small preference
     * write.
     */
    fun flushLatest() {
        if (!loadCompleted) return
        latestSnapshot?.let(::writeSnapshot)
    }

    /**
     * A playing Up Next item is removed from PlaybackQueueSnapshot.upNextQueue before playback
     * starts, while AndroidPlaybackResumeStore still knows which item is active. Re-inserting it
     * temporarily lets FuoPlayerController.synchronizePlaybackTrack identify it as Up Next after
     * process recreation instead of replacing the current main-queue entry with that track.
     *
     * The second branch is a best-effort migration for a queue that was already overwritten by the
     * old startup race: the resume plan contains the current item plus the controller's look-ahead
     * window, so at least that playable window can be recovered instead of showing an empty queue.
     */
    private fun PlaybackQueueSnapshot.reconcileRestoredSession(
        session: AndroidPlaybackResumeSnapshot?,
    ): PlaybackQueueSnapshot {
        session ?: return this
        val restoredTrack = session.currentTrack
        val knownTrackIds = (mainQueue + upNextQueue).mapTo(mutableSetOf()) { it.id }
        if (restoredTrack.id in knownTrackIds) return this

        if (mainQueue.isEmpty() && upNextQueue.isEmpty()) {
            val recoveredQueue = session.plan.requests
                .map { it.track }
                .distinctBy { it.id }
                .ifEmpty { listOf(restoredTrack) }
            val restoredIndex = recoveredQueue.indexOfFirst { it.id == restoredTrack.id }
                .takeIf { it >= 0 }
                ?: 0
            return copy(
                mainQueue = recoveredQueue,
                queueIndex = restoredIndex,
            )
        }

        return copy(upNextQueue = listOf(restoredTrack) + upNextQueue)
    }

    private fun writeSnapshot(snapshot: PlaybackQueueSnapshot) {
        preferences.edit()
            .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
    }
}
