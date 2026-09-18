package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialized, restartable workflow. The owner must call initialize() before using it and launch
 * runUntilBlocked() from an application-level scope rather than a screen's composition scope.
 * Each network operation has a durable checkpoint on either side of the request.
 */
class PlaylistMigrationCoordinator(
    private val store: PlaylistMigrationStore,
    private val provider: PlaylistMigrationProvider,
    private val minimumAutoScore: Double = 0.90,
) {
    private val mutex = Mutex()
    private val mutableTasks = MutableStateFlow<List<PlaylistMigrationTask>>(emptyList())
    val tasks: StateFlow<List<PlaylistMigrationTask>> = mutableTasks.asStateFlow()

    suspend fun initialize() = mutex.withLock {
        // A persisted in-flight write is deliberately NOT converted to a failed write. On resume,
        // writeNext() first checks the actual destination contents before sending anything.
        mutableTasks.value = store.load()
    }

    suspend fun create(id: String, source: MigrationPlaylist, targetProviderId: String): PlaylistMigrationTask = mutex.withLock {
        require(id.isNotBlank() && source.id.isNotBlank() && source.providerId.isNotBlank())
        require(targetProviderId.isNotBlank() && targetProviderId != source.providerId)
        require(mutableTasks.value.none { it.id == id }) { "Migration already exists: $id" }
        persist(PlaylistMigrationTask(id = id, source = source, targetProviderId = targetProviderId))
    }

    /** One checkpointed operation. Good for UI-driven or scheduler-driven execution. */
    suspend fun step(id: String): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        try {
            when (task.phase) {
                MigrationPhase.Loading -> loadNext(task)
                MigrationPhase.Matching -> matchNext(task)
                MigrationPhase.Writing -> writeNext(task)
                else -> task
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            persist(task.copy(
                phase = MigrationPhase.Paused,
                resumePhase = task.phase,
                error = error.message ?: "请重试",
            ))
        }
    }

    /** Stops for review, destination choice, completion or failure; never skips user confirmation. */
    suspend fun runUntilBlocked(id: String): PlaylistMigrationTask {
        while (true) {
            val task = step(id)
            if (task.phase !in setOf(MigrationPhase.Loading, MigrationPhase.Matching, MigrationPhase.Writing)) return task
        }
    }

    suspend fun chooseMatch(id: String, position: Int, candidate: MigrationTrack): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        require(task.phase == MigrationPhase.Review)
        require(candidate.providerId == task.targetProviderId)
        changeEntry(task, position) { entry ->
            require(entry.status != MigrationTrackStatus.Added)
            entry.copy(selected = candidate, status = MigrationTrackStatus.Matched, error = null)
        }
    }

    suspend fun skip(id: String, position: Int): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        require(task.phase == MigrationPhase.Review)
        changeEntry(task, position) { it.copy(selected = null, status = MigrationTrackStatus.Skipped, error = null) }
    }

    suspend fun confirmMatches(id: String): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        require(task.phase == MigrationPhase.Review)
        require(task.entries.none { it.status !in setOf(MigrationTrackStatus.Matched, MigrationTrackStatus.Skipped) }) {
            "请先检查剩余歌曲"
        }
        persist(task.copy(phase = MigrationPhase.Destination, error = null))
    }

    /** Also resolves an ambiguous create response; never silently creates another playlist. */
    suspend fun chooseDestination(id: String, playlist: MigrationPlaylist): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        require(task.phase == MigrationPhase.Destination ||
            (task.phase == MigrationPhase.Paused && task.resumePhase == MigrationPhase.Destination))
        require(playlist.providerId == task.targetProviderId && playlist.id.isNotBlank())
        persist(task.copy(destination = playlist, phase = MigrationPhase.Writing, resumePhase = null, error = null))
    }

    /** Set creationAttempted BEFORE the remote request: a timeout requires manual reconciliation. */
    suspend fun createDestination(id: String, name: String): PlaylistMigrationTask = mutex.withLock {
        var task = get(id)
        require(task.phase == MigrationPhase.Destination && task.destination == null)
        require(!task.creationAttempted) { "请确认已创建的歌单，避免重复创建" }
        require(name.isNotBlank())
        task = persist(task.copy(creationAttempted = true, requestedDestinationName = name))
        try {
            val created = provider.createPlaylist(task.targetProviderId, name)
            require(created.id.isNotBlank() && created.providerId == task.targetProviderId)
            persist(task.copy(destination = created, phase = MigrationPhase.Writing, error = null))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            persist(task.copy(
                phase = MigrationPhase.Paused,
                resumePhase = MigrationPhase.Destination,
                error = "请确认目标歌单是否已创建，再选择歌单继续",
            ))
        }
    }

    suspend fun pause(id: String): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        if (task.phase !in setOf(MigrationPhase.Loading, MigrationPhase.Matching, MigrationPhase.Writing)) task
        else persist(task.copy(phase = MigrationPhase.Paused, resumePhase = task.phase))
    }

    suspend fun retry(id: String): PlaylistMigrationTask = mutex.withLock {
        val task = get(id)
        when (task.phase) {
            MigrationPhase.Paused -> {
                val next = requireNotNull(task.resumePhase)
                persist(task.copy(
                    phase = next,
                    writePass = if (next == MigrationPhase.Writing) task.writePass + 1 else task.writePass,
                    resumePhase = null,
                    error = null,
                ))
            }
            MigrationPhase.Partial -> persist(task.copy(
                phase = MigrationPhase.Writing,
                writePass = task.writePass + 1,
                error = null,
                entries = task.entries.map { entry ->
                    when (entry.status) {
                        // A clean rejection can be attempted again as a normal matched item.
                        MigrationTrackStatus.Failed -> entry.copy(
                            status = MigrationTrackStatus.Matched,
                            error = null,
                        )
                        // An uncertain request must reconcile destination state before a resend.
                        // Adding has exactly that restart behavior in writeNext().
                        MigrationTrackStatus.Uncertain -> entry.copy(
                            status = MigrationTrackStatus.Adding,
                            error = null,
                        )
                        else -> entry
                    }
                },
            ))
            else -> task
        }
    }

    private suspend fun loadNext(task: PlaylistMigrationTask): PlaylistMigrationTask {
        if (task.sourceLoaded) return persist(task.copy(phase = MigrationPhase.Matching))
        val page = provider.loadPage(task.source, task.nextOffset)
        require(!page.hasMore || page.nextOffset > task.nextOffset) { "歌单分页没有前进" }
        val newEntries = page.tracks.mapIndexed { index, track ->
            require(track.providerId == task.source.providerId) { "来源歌曲不一致" }
            MigrationEntry(task.entries.size + index, track)
        }
        return persist(task.copy(
            entries = task.entries + newEntries,
            nextOffset = page.nextOffset,
            sourceLoaded = !page.hasMore,
            phase = if (page.hasMore) MigrationPhase.Loading else MigrationPhase.Matching,
            error = null,
        ))
    }

    private suspend fun matchNext(task: PlaylistMigrationTask): PlaylistMigrationTask {
        val entry = task.entries.firstOrNull { it.status == MigrationTrackStatus.Pending }
            ?: return persist(task.copy(phase = MigrationPhase.Review, error = null))
        val candidates = provider.candidates(entry.source, task.targetProviderId)
            .filter { it.track.providerId == task.targetProviderId }
            .distinctBy { it.track.id }
            .sortedByDescending { it.score }
        val best = candidates.firstOrNull()
        val status = when {
            best == null -> MigrationTrackStatus.Missing
            best.score >= minimumAutoScore -> MigrationTrackStatus.Matched
            else -> MigrationTrackStatus.NeedsReview
        }
        return changeEntry(task, entry.position) {
            it.copy(
                candidates = candidates,
                selected = best?.track,
                status = status,
                error = null,
            )
        }
    }

    private suspend fun writeNext(task: PlaylistMigrationTask): PlaylistMigrationTask {
        val destination = requireNotNull(task.destination) { "请先选择目标歌单" }
        // Failed/Uncertain are terminal for the current pass. This lets the background write stage
        // attempt every other matched song instead of stopping at the first per-track failure.
        // retry(Partial) explicitly turns only those entries back into retryable states.
        val entry = task.entries.firstOrNull {
            it.status == MigrationTrackStatus.Matched || it.status == MigrationTrackStatus.Adding
        } ?: return persist(task.copy(
            phase = if (task.entries.any { it.status == MigrationTrackStatus.Failed ||
                    it.status == MigrationTrackStatus.Uncertain }) MigrationPhase.Partial else MigrationPhase.Complete,
            error = null,
        ))
        val selected = requireNotNull(entry.selected)
        require(selected.providerId == task.targetProviderId)
        // Reconcile *before* every retry. This also avoids re-adding songs already in an existing list.
        val existing = provider.targetTracks(task.id, task.writePass, destination)
        if (selected.id in existing) return changeEntry(task, entry.position) {
            it.copy(status = MigrationTrackStatus.Added, error = null)
        }
        // An interrupted Adding operation is ambiguous. If the remote list does not contain it,
        // reconciliation has completed and the item can safely be sent again.
        val inFlight = changeEntry(task, entry.position) {
            it.copy(status = MigrationTrackStatus.Adding, error = null)
        }
        return try {
            if (provider.addTrack(task.id, task.writePass, destination, selected)) {
                changeEntry(inFlight, entry.position) { it.copy(status = MigrationTrackStatus.Added, error = null) }
            } else {
                changeEntry(inFlight, entry.position) {
                    it.copy(status = MigrationTrackStatus.Failed, error = "迁移失败")
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            changeEntry(inFlight, entry.position) {
                it.copy(status = MigrationTrackStatus.Uncertain, error = error.message ?: "请重试")
            }
        }
    }

    private fun get(id: String) = mutableTasks.value.first { it.id == id }

    private suspend fun changeEntry(
        task: PlaylistMigrationTask,
        position: Int,
        transform: (MigrationEntry) -> MigrationEntry,
    ): PlaylistMigrationTask {
        require(task.entries.any { it.position == position })
        return persist(task.copy(entries = task.entries.map { if (it.position == position) transform(it) else it }))
    }

    private suspend fun persist(task: PlaylistMigrationTask): PlaylistMigrationTask {
        store.save(task)
        val current = mutableTasks.value
        mutableTasks.value = if (current.any { it.id == task.id }) current.map { if (it.id == task.id) task else it }
        else listOf(task) + current
        return task
    }
}
