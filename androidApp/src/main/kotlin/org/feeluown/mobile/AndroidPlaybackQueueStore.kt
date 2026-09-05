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
        val identity = preferences.getString(KEY_QUEUE_IDENTITY, null)
            ?.let { raw -> runCatching { PlaybackQueueIdentityCodec.decode(raw) }.getOrNull() }
        val identityFingerprint = preferences.getString(KEY_QUEUE_IDENTITY_FINGERPRINT, null)
        val restoredSession = playbackResumeStore.load()
        val snapshot = persistedSnapshot
            .withMatchingIdentitySnapshot(identity, identityFingerprint)
            .reconcileRestoredPlayback(
                plan = restoredSession?.plan,
                currentTrack = restoredSession?.currentTrack,
                currentQueueEntryId = restoredSession?.currentQueueEntryId,
            )
        latestSnapshot = snapshot
        loadCompleted = true
        snapshot
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        if (!loadCompleted) return
        latestSnapshot = snapshot
        withContext(Dispatchers.IO) {
            writeSnapshot(snapshot)
        }
    }

    fun flushLatest() {
        if (!loadCompleted) return
        latestSnapshot?.let(::writeSnapshot)
    }

    private fun writeSnapshot(snapshot: PlaybackQueueSnapshot) {
        preferences.edit()
            .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
            .putString(KEY_QUEUE_IDENTITY, PlaybackQueueIdentityCodec.encode(snapshot.toIdentitySnapshot()))
            .putString(KEY_QUEUE_IDENTITY_FINGERPRINT, snapshot.queueIdentityFingerprint())
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        private const val KEY_QUEUE_IDENTITY = "queue_identity"
        private const val KEY_QUEUE_IDENTITY_FINGERPRINT = "queue_identity_fingerprint"
    }
}
