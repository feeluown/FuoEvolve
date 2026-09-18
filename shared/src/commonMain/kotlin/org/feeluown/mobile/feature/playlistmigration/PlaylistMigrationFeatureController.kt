package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

private val BACKGROUND_RUNNABLE_PHASES = setOf(
    MigrationPhase.Loading,
    MigrationPhase.Matching,
    MigrationPhase.Writing,
)

/** A target must support both playlist creation and writing tracks to the created playlist. */
internal fun ProviderCapabilities.canCreateMigrationDestination(): Boolean =
    canAddSongToPlaylist && canCreatePlaylist

/**
 * App-scoped feature owner. Screens observe state and dispatch actions; they never own a running
 * migration job. Durable platform schedulers may resume the same persisted checkpoints after a
 * process restart without resubmitting already-confirmed writes.
 */
class PlaylistMigrationFeatureController(
    val coordinator: PlaylistMigrationCoordinator,
    private val provider: ProviderPlaylistMigrationAdapter,
    private val registry: ProviderRegistryRepository,
    private val search: ProviderSearchRepository,
    private val scope: CoroutineScope,
) {
    val tasks: StateFlow<List<PlaylistMigrationTask>> = coordinator.tasks
    private val ready = CompletableDeferred<Unit>()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableSources = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val sources: StateFlow<List<ProviderInfo>> = mutableSources.asStateFlow()
    private val mutableTargets = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val targets: StateFlow<List<ProviderInfo>> = mutableTargets.asStateFlow()
    private val mutableCreatableProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val creatableProviderIds: StateFlow<Set<String>> = mutableCreatableProviderIds.asStateFlow()
    private val mutablePlaylists = MutableStateFlow<List<ProviderPlaylist>>(emptyList())
    val playlists: StateFlow<List<ProviderPlaylist>> = mutablePlaylists.asStateFlow()
    private val mutableCandidates = MutableStateFlow<List<MusicTrack>>(emptyList())
    val searchResults: StateFlow<List<MusicTrack>> = mutableCandidates.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        bindPlaylistMigrationBackgroundRunner(this, scope)
        scope.launch {
            try {
                coordinator.initialize()
                ready.complete(Unit)
            } catch (cancel: CancellationException) {
                ready.cancel()
                throw cancel
            } catch (failure: Exception) {
                mutableError.value = failure.message ?: "读取迁移记录失败"
                ready.completeExceptionally(failure)
            }
        }
    }

    fun refreshProviders() = action {
        val registered = registry.providers()
        val capabilities = registry.providerCapabilities().associateBy { it.providerId }
        val features = providerCatalogFeatures()
        val readable = features.filter {
            it.category == ProviderFeatureCategory.MinePlaylists &&
                it.contentType == ProviderContentType.Playlists
        }.mapTo(mutableSetOf()) { it.providerId }
        mutableSources.value = registered.filter { it.providerId in readable }
        mutableCreatableProviderIds.value = capabilities.values.filter {
            it.canCreateMigrationDestination()
        }.mapTo(mutableSetOf()) { it.providerId }
        mutableTargets.value = registered.filter {
            capabilities[it.providerId]?.canAddSongToPlaylist == true
        }
    }

    fun loadPlaylists(providerId: String) = action {
        mutablePlaylists.value = emptyList()
        mutablePlaylists.value = provider.ownedPlaylists(providerId)
    }

    fun searchAlternatives(taskId: String, keyword: String) = action {
        val task = requireNotNull(tasks.value.firstOrNull { it.id == taskId })
        require(keyword.isNotBlank())
        mutableCandidates.value = emptyList()
        mutableCandidates.value = search.search(keyword.trim(), task.targetProviderId)
            .filter { it.source == task.targetProviderId }
    }

    fun start(source: ProviderPlaylist, targetProviderId: String) = action {
        require(source.providerId != targetProviderId) { "请选择其他目标" }
        require(targets.value.any { it.providerId == targetProviderId }) { "当前目标暂不支持迁移" }
        val task = coordinator.create(
            id = "migration-${Random.nextLong(1, Long.MAX_VALUE).toString(36)}",
            source = source.toMigrationPlaylist(),
            targetProviderId = targetProviderId,
        )
        run(task.id)
    }

    fun chooseMatch(taskId: String, position: Int, track: MigrationTrack) = action {
        coordinator.chooseMatch(taskId, position, track)
    }

    fun skip(taskId: String, position: Int) = action { coordinator.skip(taskId, position) }

    /** Only an explicit click in the review UI may skip uncertain matches in bulk. */
    fun skipUnresolved(taskId: String) = action {
        val task = requireNotNull(tasks.value.firstOrNull { it.id == taskId })
        task.entries.filter {
            it.status == MigrationTrackStatus.NeedsReview || it.status == MigrationTrackStatus.Missing
        }.forEach { coordinator.skip(taskId, it.position) }
    }

    fun confirm(taskId: String) = action { coordinator.confirmMatches(taskId) }

    fun chooseDestination(taskId: String, playlist: ProviderPlaylist) = action {
        val task = coordinator.chooseDestination(taskId, playlist.toMigrationPlaylist())
        run(task.id)
    }

    fun createDestination(taskId: String, name: String) = action {
        val current = requireNotNull(tasks.value.firstOrNull { it.id == taskId })
        require(registry.providerCapabilities().any {
            it.providerId == current.targetProviderId && it.canCreateMigrationDestination()
        }) { "当前目标暂不支持新建歌单，请先创建后选择" }
        val task = coordinator.createDestination(taskId, name.trim())
        if (task.phase == MigrationPhase.Writing) run(task.id)
    }

    fun pause(taskId: String) = action {
        coordinator.pause(taskId)
        // Pausing is checkpoint-safe: an in-flight provider request finishes first under the
        // coordinator mutex. The runner then sees Paused and exits.
    }

    fun retry(taskId: String) = action {
        val task = coordinator.retry(taskId)
        if (task.phase in BACKGROUND_RUNNABLE_PHASES) run(task.id)
    }

    /**
     * Executes a bounded amount of one background stage. A stale conversion worker is never
     * allowed to cross Review and start destination writing, and a stale writing worker never
     * starts conversion work. A stale worker also emits no progress for a phase it does not own,
     * which prevents delayed platform callbacks from producing misleading terminal notifications.
     */
    suspend fun runBackgroundSlice(
        taskId: String,
        stage: PlaylistMigrationBackgroundStage,
        maxSteps: Int = 24,
        onProgress: suspend (PlaylistMigrationTask) -> Unit = {},
    ): Boolean {
        require(maxSteps > 0)
        ready.await()
        val initial = tasks.value.firstOrNull { it.id == taskId } ?: return false
        if (!stage.accepts(initial.phase)) return false
        onProgress(initial)
        repeat(maxSteps) {
            val current = tasks.value.firstOrNull { it.id == taskId } ?: return false
            if (!stage.accepts(current.phase)) return false
            val next = coordinator.step(taskId)
            onProgress(next)
            if (!stage.accepts(next.phase)) return false
        }
        return tasks.value.firstOrNull { it.id == taskId }?.let { stage.accepts(it.phase) } == true
    }

    fun clearError() { mutableError.value = null }

    private suspend fun providerCatalogFeatures(): List<ProviderFeature> = providerFeatures()

    /** The adapter owns playlist catalog access, avoiding a second provider facade in the UI. */
    private suspend fun providerFeatures(): List<ProviderFeature> = provider.features()

    private fun run(taskId: String) {
        val task = tasks.value.firstOrNull { it.id == taskId } ?: return
        val stage = task.backgroundStageOrNull() ?: return
        if (enqueuePlaylistMigrationBackground(task)) return
        val jobKey = "$taskId:${stage.name}"
        if (jobs[jobKey]?.isActive == true) return
        jobs[jobKey] = scope.launch {
            try {
                while (runBackgroundSlice(taskId, stage, maxSteps = 64)) {
                    // Keep the in-process fallback bounded per slice while the app remains alive.
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutableError.value = failure.message ?: "迁移失败，请重试"
            } finally {
                jobs.remove(jobKey)
            }
        }
    }

    private fun action(block: suspend () -> Unit): Job = scope.launch {
        try {
            ready.await()
            mutableBusy.value = true
            mutableError.value = null
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            mutableError.value = failure.message ?: "操作失败，请重试"
        } finally {
            mutableBusy.value = false
        }
    }
}
