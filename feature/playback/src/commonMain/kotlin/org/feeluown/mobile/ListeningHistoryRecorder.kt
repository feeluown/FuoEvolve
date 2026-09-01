package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val LISTENING_CHECKPOINT_INTERVAL_MS = 30_000L
private const val LISTENING_UNKNOWN_DURATION_QUALIFIED_MS = 30_000L
private const val LISTENING_MIN_SCROBBLE_DURATION_MS = 30_000L
private const val LISTENING_MAX_QUALIFIED_THRESHOLD_MS = 240_000L
private val listeningMonotonicOrigin = TimeSource.Monotonic.markNow()

class ListeningHistoryRecorder(
    private val sink: ListeningHistorySink,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val monotonicMillis: () -> Long = {
        listeningMonotonicOrigin.elapsedNow().inWholeMilliseconds
    },
) {
    private val writes = Channel<ListeningHistoryRecord>(Channel.UNLIMITED)
    private val recorderInstanceId = nowMillis()
    private var nextSessionOrdinal = 0L
    private var active: ActiveListeningSession? = null
    private var checkpointJob: Job? = null

    init {
        scope.launch {
            for (record in writes) {
                runCatching { sink.upsert(record) }
            }
        }
    }

    fun onPlaybackState(
        state: PlaybackState,
        queueState: PlaybackQueueState?,
    ) {
        val currentTrack = state.currentTrack
        val currentPrimary = currentTrack?.logicalListeningResource()
        val currentActive = active
        if (currentActive != null && currentPrimary != null) {
            val resourceChanged = currentActive.primary.resourceKey != currentPrimary.resourceKey
            val startSequenceChanged = queueState != null &&
                currentActive.playbackStartSequence != queueState.playbackStartSequence
            val reason = queueState?.lastPlaybackStartReason
            val playbackPartTransition = startSequenceChanged &&
                currentActive.currentPartIndex >= 0 &&
                state.currentPartIndex >= 0 &&
                currentActive.currentPartIndex != state.currentPartIndex
            val transactionRestarted = startSequenceChanged &&
                reason != PlaybackStartReason.RESUME &&
                reason != PlaybackStartReason.RESTORE_SESSION &&
                !playbackPartTransition
            if (resourceChanged || transactionRestarted) {
                finalizeActive(ListeningCompletionReason.Changed)
            } else if (startSequenceChanged) {
                currentActive.playbackStartSequence = queueState.playbackStartSequence
            }
        }

        when (state.status) {
            PlayerStatus.Playing -> {
                val track = currentTrack ?: return
                val session = active ?: startSession(track, state, queueState)
                refreshSession(session, track, state, queueState)
                if (session.playingSinceMonotonicMs == null) {
                    session.playingSinceMonotonicMs = monotonicMillis()
                }
                ensureCheckpointing()
            }
            PlayerStatus.Paused -> {
                val session = active ?: return
                currentTrack?.let { refreshSession(session, it, state, queueState) }
                pauseAndCheckpoint(session)
            }
            PlayerStatus.Ended -> {
                active?.let { session ->
                    currentTrack?.let { refreshSession(session, it, state, queueState) }
                }
                finalizeActive(ListeningCompletionReason.Ended)
            }
            PlayerStatus.Error -> {
                active?.let { session ->
                    currentTrack?.let { refreshSession(session, it, state, queueState) }
                }
                finalizeActive(ListeningCompletionReason.Error)
            }
            PlayerStatus.Idle -> finalizeActive(ListeningCompletionReason.Stopped)
            PlayerStatus.Loading -> {
                val session = active ?: return
                currentTrack?.let { refreshSession(session, it, state, queueState) }
                pauseAndCheckpoint(session)
            }
        }
    }

    private fun startSession(
        track: MusicTrack,
        state: PlaybackState,
        queueState: PlaybackQueueState?,
    ): ActiveListeningSession {
        val startedAt = nowMillis()
        val primary = track.logicalListeningResource()
        val session = ActiveListeningSession(
            sessionKey = "$recorderInstanceId:${++nextSessionOrdinal}:${primary.resourceKey}",
            primary = primary,
            startedAtMillis = startedAt,
            startReason = queueState?.lastPlaybackStartReason.toListeningStartReason(),
            playbackStartSequence = queueState?.playbackStartSequence ?: 0L,
            currentPartIndex = state.currentPartIndex,
            durationMs = state.durationMs.takeIf { it > 0L } ?: track.durationMs,
            contextSessionKey = listeningContextSessionKey(queueState),
            resources = track.listeningRelations(queueState),
            playingSinceMonotonicMs = monotonicMillis(),
        )
        active = session
        enqueue(session.snapshot(updatedAtMillis = startedAt))
        return session
    }

    private fun refreshSession(
        session: ActiveListeningSession,
        track: MusicTrack,
        state: PlaybackState,
        queueState: PlaybackQueueState?,
    ) {
        val primary = track.logicalListeningResource()
        if (primary.resourceKey != session.primary.resourceKey) return
        session.primary = primary
        if (state.currentPartIndex >= 0) {
            session.currentPartIndex = state.currentPartIndex
        }
        session.durationMs = state.durationMs.takeIf { it > 0L }
            ?: track.durationMs
            ?: session.durationMs
        session.resources = track.listeningRelations(queueState)
    }

    private fun listeningContextSessionKey(queueState: PlaybackQueueState?): String? {
        val state = queueState ?: return null
        val context = state.listeningContext ?: return null
        if (state.currentIsUpNext) return null
        return buildString {
            append(recorderInstanceId)
            append(":context:")
            append(state.listeningContextSequence)
            append(':')
            append(context.type.name)
            append(':')
            append(context.sourceId.length)
            append(':')
            append(context.sourceId)
            append(':')
            append(context.resourceId)
        }
    }

    private fun pauseAndCheckpoint(session: ActiveListeningSession) {
        val wasPlaying = session.playingSinceMonotonicMs != null
        accruePlayingTime(session, continuePlaying = false)
        checkpointJob?.cancel()
        checkpointJob = null
        if (wasPlaying) {
            enqueue(session.snapshot(updatedAtMillis = nowMillis()))
        }
    }

    private fun finalizeActive(reason: ListeningCompletionReason) {
        val session = active ?: return
        accruePlayingTime(session, continuePlaying = false)
        checkpointJob?.cancel()
        checkpointJob = null
        val endedAt = nowMillis()
        enqueue(
            session.snapshot(
                updatedAtMillis = endedAt,
                endedAtMillis = endedAt,
                completionReason = reason,
            )
        )
        active = null
    }

    private fun ensureCheckpointing() {
        if (checkpointJob?.isActive == true) return
        checkpointJob = scope.launch {
            while (isActive) {
                delay(LISTENING_CHECKPOINT_INTERVAL_MS)
                val session = active ?: break
                if (session.playingSinceMonotonicMs != null) {
                    accruePlayingTime(session, continuePlaying = true)
                    enqueue(session.snapshot(updatedAtMillis = nowMillis()))
                }
            }
        }
    }

    private fun accruePlayingTime(
        session: ActiveListeningSession,
        continuePlaying: Boolean,
    ) {
        val startedAt = session.playingSinceMonotonicMs ?: return
        val current = monotonicMillis()
        session.playedMs += (current - startedAt).coerceAtLeast(0L)
        session.playingSinceMonotonicMs = current.takeIf { continuePlaying }
    }

    private fun enqueue(record: ListeningHistoryRecord) {
        writes.trySend(record)
    }
}

private data class ActiveListeningSession(
    val sessionKey: String,
    var primary: ListeningResourceSnapshot,
    val startedAtMillis: Long,
    val startReason: ListeningStartReason,
    var playbackStartSequence: Long,
    var currentPartIndex: Int,
    var durationMs: Long?,
    val contextSessionKey: String?,
    var resources: List<ListeningResourceRelation>,
    var playedMs: Long = 0L,
    var playingSinceMonotonicMs: Long? = null,
) {
    fun snapshot(
        updatedAtMillis: Long,
        endedAtMillis: Long? = null,
        completionReason: ListeningCompletionReason? = null,
    ): ListeningHistoryRecord = ListeningHistoryRecord(
        sessionKey = sessionKey,
        primaryResourceKey = primary.resourceKey,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        playedMs = playedMs,
        durationMs = durationMs,
        qualified = isQualifiedListening(playedMs, durationMs),
        startReason = startReason,
        completionReason = completionReason,
        contextSessionKey = contextSessionKey,
        resources = resources,
        updatedAtMillis = updatedAtMillis,
    )
}

private fun PlaybackStartReason?.toListeningStartReason(): ListeningStartReason = when (this) {
    PlaybackStartReason.USER_SELECTION -> ListeningStartReason.UserSelection
    PlaybackStartReason.PLAYLIST_REPLACE -> ListeningStartReason.PlaylistReplace
    PlaybackStartReason.AUTO_NEXT -> ListeningStartReason.AutoNext
    PlaybackStartReason.RESUME -> ListeningStartReason.Resume
    PlaybackStartReason.RESTORE_SESSION -> ListeningStartReason.RestoreSession
    PlaybackStartReason.RECOVERY -> ListeningStartReason.Unknown
    null -> ListeningStartReason.Unknown
}

private fun isQualifiedListening(playedMs: Long, durationMs: Long?): Boolean {
    val duration = durationMs?.takeIf { it > 0L }
        ?: return playedMs >= LISTENING_UNKNOWN_DURATION_QUALIFIED_MS
    if (duration <= LISTENING_MIN_SCROBBLE_DURATION_MS) return false
    val threshold = minOf(duration / 2L, LISTENING_MAX_QUALIFIED_THRESHOLD_MS)
    return playedMs >= threshold
}

private fun MusicTrack.listeningRelations(queueState: PlaybackQueueState?): List<ListeningResourceRelation> = buildList {
    val primary = logicalListeningResource()
    add(ListeningResourceRelation(primary, ListeningResourceRelationType.Primary))

    artistRefs.forEach { artist ->
        add(
            ListeningResourceRelation(
                resource = ListeningResourceSnapshot(
                    resourceKey = listeningResourceKey(ListeningResourceType.Artist, artist.sourceId, artist.id),
                    type = ListeningResourceType.Artist,
                    sourceId = artist.sourceId,
                    sourceResourceId = artist.id,
                    title = artist.title,
                    coverUrl = artist.coverUrl,
                ),
                relation = ListeningResourceRelationType.Artist,
            )
        )
    }

    albumItemId?.takeIf { it.isNotBlank() }?.let { albumId ->
        val sourceId = logicalListeningSourceId()
        add(
            ListeningResourceRelation(
                resource = ListeningResourceSnapshot(
                    resourceKey = listeningResourceKey(ListeningResourceType.Album, sourceId, albumId),
                    type = ListeningResourceType.Album,
                    sourceId = sourceId,
                    sourceResourceId = albumId,
                    title = originalAlbum?.takeIf { isSmartReplacement } ?: album,
                    coverUrl = originalCoverUrl?.takeIf { isSmartReplacement } ?: coverUrl,
                ),
                relation = ListeningResourceRelationType.Album,
            )
        )
    }

    localDirectoryId?.takeIf { it.isNotBlank() }?.let { directoryId ->
        add(
            ListeningResourceRelation(
                resource = ListeningResourceSnapshot(
                    resourceKey = listeningResourceKey(ListeningResourceType.LocalDirectory, "local", directoryId),
                    type = ListeningResourceType.LocalDirectory,
                    sourceId = "local",
                    sourceResourceId = directoryId,
                    title = directoryId,
                ),
                relation = ListeningResourceRelationType.LocalDirectory,
            )
        )
    }

    if (queueState?.currentIsUpNext != true) {
        val explicitContext = queueState?.listeningContext
        if (explicitContext != null) {
            add(explicitContext.toListeningRelation())
        } else {
            queueState?.queuePlaylistId?.takeIf { it.isNotBlank() }?.let { playlistId ->
                add(
                    ListeningResourceRelation(
                        resource = ListeningResourceSnapshot(
                            resourceKey = listeningResourceKey(ListeningResourceType.Playlist, "context", playlistId),
                            type = ListeningResourceType.Playlist,
                            sourceId = "context",
                            sourceResourceId = playlistId,
                            title = playlistId,
                        ),
                        relation = ListeningResourceRelationType.PlaylistContext,
                    )
                )
            }
            queueState?.queueFeature?.let { feature ->
                add(
                    ListeningResourceRelation(
                        resource = ListeningResourceSnapshot(
                            resourceKey = listeningResourceKey(ListeningResourceType.Feature, feature.providerId, feature.id),
                            type = ListeningResourceType.Feature,
                            sourceId = feature.providerId,
                            sourceResourceId = feature.id,
                            title = feature.title,
                            subtitle = feature.providerName,
                        ),
                        relation = ListeningResourceRelationType.FeatureContext,
                    )
                )
            }
        }
    }

    resolvedListeningResource()?.let { resolved ->
        add(ListeningResourceRelation(resolved, ListeningResourceRelationType.ResolvedSource))
    }
}.distinctBy { relation -> relation.relation to relation.resource.resourceKey }

private fun PlaybackContextSnapshot.toListeningRelation(): ListeningResourceRelation {
    val (resourceType, relationType) = when (type) {
        PlaybackContextType.Playlist -> ListeningResourceType.Playlist to ListeningResourceRelationType.PlaylistContext
        PlaybackContextType.Feature -> ListeningResourceType.Feature to ListeningResourceRelationType.FeatureContext
        PlaybackContextType.Album -> ListeningResourceType.Album to ListeningResourceRelationType.Album
        PlaybackContextType.Artist -> ListeningResourceType.Artist to ListeningResourceRelationType.Artist
        PlaybackContextType.Search -> ListeningResourceType.Search to ListeningResourceRelationType.SearchContext
        PlaybackContextType.LocalDirectory -> ListeningResourceType.LocalDirectory to ListeningResourceRelationType.LocalDirectory
    }
    return ListeningResourceRelation(
        resource = ListeningResourceSnapshot(
            resourceKey = listeningResourceKey(resourceType, sourceId, resourceId),
            type = resourceType,
            sourceId = sourceId,
            sourceResourceId = resourceId,
            title = title,
            subtitle = subtitle,
            coverUrl = coverUrl,
        ),
        relation = relationType,
    )
}

private fun MusicTrack.logicalListeningResource(): ListeningResourceSnapshot {
    val sourceId = logicalListeningSourceId()
    val resourceId = originalId?.takeIf { isSmartReplacement && it.isNotBlank() } ?: id
    return ListeningResourceSnapshot(
        resourceKey = listeningResourceKey(ListeningResourceType.Track, sourceId, resourceId),
        type = ListeningResourceType.Track,
        sourceId = sourceId,
        sourceResourceId = resourceId,
        title = originalTitle?.takeIf { isSmartReplacement && it.isNotBlank() } ?: title,
        subtitle = originalArtists?.takeIf { isSmartReplacement && it.isNotBlank() } ?: artists,
        coverUrl = originalCoverUrl?.takeIf { isSmartReplacement } ?: coverUrl,
    )
}

private fun MusicTrack.resolvedListeningResource(): ListeningResourceSnapshot? {
    val resolvedId = replacementId?.takeIf { it.isNotBlank() } ?: return null
    val resolvedSource = replacementSource?.takeIf { it.isNotBlank() } ?: return null
    return ListeningResourceSnapshot(
        resourceKey = listeningResourceKey(ListeningResourceType.Track, resolvedSource, resolvedId),
        type = ListeningResourceType.Track,
        sourceId = resolvedSource,
        sourceResourceId = resolvedId,
        title = replacementTitle?.takeIf { it.isNotBlank() } ?: title,
        subtitle = replacementArtists?.takeIf { it.isNotBlank() } ?: artists,
        coverUrl = replacementCoverUrl ?: coverUrl,
    )
}

private fun MusicTrack.logicalListeningSourceId(): String {
    if (isSmartReplacement) {
        originalSource?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return source.takeIf { it.isNotBlank() }
        ?: when (sourceType) {
            TrackSourceType.LocalMediaStore -> "local"
            TrackSourceType.Downloaded -> "downloaded"
            TrackSourceType.Provider -> "provider"
        }
}

private fun listeningResourceKey(
    type: ListeningResourceType,
    sourceId: String,
    resourceId: String,
): String = "${type.name}:${sourceId.length}:$sourceId:$resourceId"
