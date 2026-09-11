package org.feeluown.mobile.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val HOME_PROVIDER_TIMEOUT_MS = 30_000L

enum class HomeTopSection {
    Recommend,
    Explore,
    Mine,
}

enum class HomeMineSection {
    Playlists,
    Artists,
    Albums,
    LocalMusic,
}

enum class HomePlaylistFilter {
    UserPlaylists,
    FavoritePlaylists,
    Local,
}

enum class HomeDisplaySurface {
    Recommend,
    Explore,
    Mine,
}

enum class HomeFeatureKind {
    Recommend,
    Explore,
    MinePlaylists,
    MineFavoritePlaylists,
    MineContent,
}

data class HomePreferencesSnapshot<Stats>(
    val isLoaded: Boolean,
    val homeSection: HomeTopSection,
    val mineSection: HomeMineSection,
    val playlistFilter: HomePlaylistFilter,
    val playlistPlaybackStats: Stats,
    val errorMessage: String? = null,
)

interface HomePreferencesPort<Stats> {
    val state: StateFlow<HomePreferencesSnapshot<Stats>>
    suspend fun setHomeSection(section: HomeTopSection)
    suspend fun setMineSection(section: HomeMineSection)
    suspend fun setPlaylistFilter(filter: HomePlaylistFilter)
}

data class HomeCatalogSnapshot<Provider, Feature>(
    val isInitialized: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val features: List<Feature> = emptyList(),
    val availableProviderIds: Set<String> = emptySet(),
    val enabledProviderIds: Set<String> = emptySet(),
    val displayProviderIds: Map<HomeDisplaySurface, Set<String>> = emptyMap(),
    val loggedInProviderIds: Set<String> = emptySet(),
    val creatablePlaylistProviderIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

interface HomeCatalogPort<Provider, Feature> {
    val state: StateFlow<HomeCatalogSnapshot<Provider, Feature>>
    fun providerId(provider: Provider): String
}

data class HomeMutationResult(
    val success: Boolean,
    val message: String,
)

interface HomeContentPort<Feature, Content, Track, Playlist> {
    suspend fun loadFeaturePage(feature: Feature, offset: Int): Content
    suspend fun createPlaylist(providerId: String, name: String): HomeMutationResult

    fun featureId(feature: Feature): String
    fun featureProviderId(feature: Feature): String
    fun featureTitle(feature: Feature): String
    fun featureKind(feature: Feature): HomeFeatureKind?
    fun featureRequiresLogin(feature: Feature): Boolean
    fun featureContentTypeKey(feature: Feature): String
    fun isDeferredFeature(feature: Feature): Boolean
    fun isDynamicQueueFeature(feature: Feature): Boolean

    fun contentFeature(content: Content): Feature
    fun contentTracks(content: Content): List<Track>
    fun contentPlaylists(content: Content): List<Playlist>
    fun contentNextOffset(content: Content): Int
    fun contentHasMore(content: Content): Boolean
    fun contentErrorMessage(content: Content): String?
    fun loginRequiredContent(feature: Feature): Content
    fun deferredContent(feature: Feature): Content
    fun errorContent(feature: Feature, message: String): Content

    fun trackKey(track: Track): String
    fun playlistKey(playlist: Playlist): String
    fun errorMessage(throwable: Throwable, providerId: String? = null): String
}

interface HomePlaybackPort<Feature, Track> {
    fun playFeatureTracks(tracks: List<Track>, index: Int, feature: Feature)
}

interface HomeLocalLibraryPort {
    fun refreshLocalPlaylists()
    fun refreshLocalMusic()
    fun ensureLocalMusic()
}

data class HomeFeatureState<Content, Stats>(
    val homeSection: HomeTopSection,
    val mineSection: HomeMineSection,
    val playlistFilter: HomePlaylistFilter,
    val recommendSections: List<Content> = emptyList(),
    val exploreSections: List<Content> = emptyList(),
    val mineSections: List<Content> = emptyList(),
    val minePlaylistSections: List<Content> = emptyList(),
    val mineFavoritePlaylistSections: List<Content> = emptyList(),
    val playlistPlaybackStats: Stats,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface HomeFeatureOwner<Provider, Feature, Content, Track, Playlist, Stats> {
    val state: StateFlow<HomeFeatureState<Content, Stats>>

    fun setHomeSection(section: HomeTopSection)
    fun setMineSection(section: HomeMineSection)
    fun setPlaylistFilter(filter: HomePlaylistFilter)
    fun markRefreshNeeded(section: HomeTopSection)
    fun refreshCurrentSectionIfNeeded()
    fun refreshHome(section: HomeTopSection)
    fun refreshMine()
    fun ensureInitialContent()

    fun playFeature(section: Content, index: Int = 0)
    fun playAllFeature(section: Content)

    fun createProviderPlaylist(providerId: String, name: String)
    fun creatablePlaylistProviders(): List<Provider>
    fun isFavoriteMinePlaylist(playlist: Playlist): Boolean
}

fun <Provider, Feature, Content, Track, Playlist, Stats> createHomeFeatureOwner(
    preferences: HomePreferencesPort<Stats>,
    catalog: HomeCatalogPort<Provider, Feature>,
    content: HomeContentPort<Feature, Content, Track, Playlist>,
    playback: HomePlaybackPort<Feature, Track>,
    localLibrary: HomeLocalLibraryPort,
    scope: CoroutineScope,
): HomeFeatureOwner<Provider, Feature, Content, Track, Playlist, Stats> =
    DefaultHomeFeatureOwner(
        preferences = preferences,
        catalog = catalog,
        content = content,
        playback = playback,
        localLibrary = localLibrary,
        scope = scope,
    )

private class DefaultHomeFeatureOwner<Provider, Feature, Content, Track, Playlist, Stats>(
    private val preferences: HomePreferencesPort<Stats>,
    private val catalog: HomeCatalogPort<Provider, Feature>,
    private val content: HomeContentPort<Feature, Content, Track, Playlist>,
    private val playback: HomePlaybackPort<Feature, Track>,
    private val localLibrary: HomeLocalLibraryPort,
    private val scope: CoroutineScope,
) : HomeFeatureOwner<Provider, Feature, Content, Track, Playlist, Stats> {
    private val initialPreferences = preferences.state.value
    private val mutableState = MutableStateFlow(
        HomeFeatureState<Content, Stats>(
            homeSection = initialPreferences.homeSection,
            mineSection = initialPreferences.mineSection,
            playlistFilter = initialPreferences.playlistFilter,
            playlistPlaybackStats = initialPreferences.playlistPlaybackStats,
        ),
    )
    override val state: StateFlow<HomeFeatureState<Content, Stats>> = mutableState.asStateFlow()

    private var recommendRefreshSerial = 0L
    private var exploreRefreshSerial = 0L
    private var minePlaylistRefreshSerial = 0L
    private var mineContentRefreshSerial = 0L
    private var initialRefreshStarted = false
    private val refreshNeeded = mutableSetOf<HomeTopSection>()

    init {
        scope.launch {
            combine(preferences.state, catalog.state) { preferenceState, catalogState ->
                preferenceState to catalogState
            }.collect { (preferenceState, catalogState) ->
                val current = state.value
                mutableState.value = current.copy(
                    homeSection = preferenceState.homeSection,
                    mineSection = preferenceState.mineSection,
                    playlistFilter = preferenceState.playlistFilter,
                    playlistPlaybackStats = preferenceState.playlistPlaybackStats,
                    errorMessage = current.errorMessage ?: catalogState.errorMessage ?: preferenceState.errorMessage,
                )
                if (preferenceState.isLoaded && catalogState.isInitialized) ensureInitialContent()
            }
        }
    }

    override fun setHomeSection(section: HomeTopSection) {
        if (state.value.homeSection == section) return
        mutableState.value = state.value.copy(homeSection = section)
        scope.launch { preferences.setHomeSection(section) }
        when (section) {
            HomeTopSection.Recommend -> refreshHomeIfNeeded(section)
            HomeTopSection.Explore -> refreshHomeIfNeeded(section)
            HomeTopSection.Mine -> refreshMineIfNeeded()
        }
    }

    override fun setMineSection(section: HomeMineSection) {
        if (state.value.mineSection == section) return
        mutableState.value = state.value.copy(mineSection = section)
        scope.launch { preferences.setMineSection(section) }
        refreshMineIfNeeded()
    }

    override fun setPlaylistFilter(filter: HomePlaylistFilter) {
        if (state.value.playlistFilter == filter) return
        mutableState.value = state.value.copy(playlistFilter = filter)
        scope.launch { preferences.setPlaylistFilter(filter) }
        if (state.value.minePlaylistSections.isEmpty() && state.value.mineFavoritePlaylistSections.isEmpty()) {
            refreshMinePlaylists()
        }
    }

    override fun markRefreshNeeded(section: HomeTopSection) {
        refreshNeeded += section
    }

    override fun refreshCurrentSectionIfNeeded() {
        if (!initialRefreshStarted) return
        when (state.value.homeSection) {
            HomeTopSection.Recommend -> refreshHomeIfNeeded(HomeTopSection.Recommend)
            HomeTopSection.Explore -> refreshHomeIfNeeded(HomeTopSection.Explore)
            HomeTopSection.Mine -> refreshMineIfNeeded()
        }
    }

    override fun ensureInitialContent() {
        if (initialRefreshStarted) return
        initialRefreshStarted = true
        when (state.value.homeSection) {
            HomeTopSection.Recommend -> {
                refreshNeeded.remove(HomeTopSection.Recommend)
                refreshHome(HomeTopSection.Recommend)
            }
            HomeTopSection.Explore -> {
                refreshNeeded.remove(HomeTopSection.Explore)
                refreshHome(HomeTopSection.Explore)
            }
            HomeTopSection.Mine -> {
                refreshMineIfNeeded()
            }
        }
    }

    override fun refreshHome(section: HomeTopSection) {
        refreshNeeded.remove(section)
        if (section == HomeTopSection.Mine) {
            refreshMine()
            return
        }
        val serial = when (section) {
            HomeTopSection.Recommend -> ++recommendRefreshSerial
            HomeTopSection.Explore -> ++exploreRefreshSerial
            HomeTopSection.Mine -> error("mine is loaded separately")
        }
        fun current(): Boolean = when (section) {
            HomeTopSection.Recommend -> serial == recommendRefreshSerial
            HomeTopSection.Explore -> serial == exploreRefreshSerial
            HomeTopSection.Mine -> false
        }
        scope.launch {
            if (current()) {
                publishLoading(if (section == HomeTopSection.Recommend) "正在加载推荐" else "正在加载探索")
            }
            val result = runCatching {
                val catalogState = catalog.state.value
                val kind = if (section == HomeTopSection.Recommend) HomeFeatureKind.Recommend else HomeFeatureKind.Explore
                val display = if (section == HomeTopSection.Recommend) HomeDisplaySurface.Recommend else HomeDisplaySurface.Explore
                val selectedIds = selectedProviderIdsFor(display, catalogState)
                val currentSections = if (section == HomeTopSection.Recommend) {
                    state.value.recommendSections
                } else {
                    state.value.exploreSections
                }
                loadSectionsIncrementally(
                    features = catalogState.features.filter { feature ->
                        content.featureKind(feature) == kind && content.featureProviderId(feature) in selectedIds
                    },
                    currentSections = currentSections,
                    deferFeature = content::isDeferredFeature,
                ) { sections ->
                    if (!current()) return@loadSectionsIncrementally
                    mutableState.value = if (section == HomeTopSection.Recommend) {
                        state.value.copy(recommendSections = sections)
                    } else {
                        state.value.copy(exploreSections = sections)
                    }
                }
            }
            if (current()) {
                result.onSuccess { sections ->
                    finishLoading(if (sections.isEmpty()) "暂无内容" else "已更新")
                }.onFailure(::publishError)
            }
        }
    }

    override fun refreshMine() {
        refreshNeeded.remove(HomeTopSection.Mine)
        when (state.value.mineSection) {
            HomeMineSection.Playlists -> refreshMinePlaylists()
            HomeMineSection.Artists, HomeMineSection.Albums -> refreshMineContent()
            HomeMineSection.LocalMusic -> localLibrary.refreshLocalMusic()
        }
    }

    override fun playFeature(section: Content, index: Int) {
        val tracks = content.contentTracks(section)
        if (index !in tracks.indices) return
        playback.playFeatureTracks(tracks, index, content.contentFeature(section))
    }

    override fun playAllFeature(section: Content) {
        val feature = content.contentFeature(section)
        val tracks = content.contentTracks(section)
        if (content.isDynamicQueueFeature(feature)) {
            if (tracks.isNotEmpty()) {
                playback.playFeatureTracks(tracks, 0, feature)
            } else {
                loadDynamicFeatureAndPlay(feature)
            }
            return
        }
        if (tracks.isEmpty()) return
        if (!content.contentHasMore(section)) {
            playback.playFeatureTracks(tracks, 0, feature)
            return
        }
        scope.launch {
            publishLoading("正在加载${content.featureTitle(feature)}")
            runCatching {
                loadAllHomeTracks(section, content)
            }.onSuccess { allTracks ->
                if (allTracks.isEmpty()) {
                    finishLoading("暂无可播放歌曲")
                } else {
                    playback.playFeatureTracks(allTracks, 0, feature)
                    finishLoading("已加载全部歌曲")
                }
            }.onFailure(::publishError)
        }
    }

    override fun creatablePlaylistProviders(): List<Provider> {
        val catalogState = catalog.state.value
        return catalogState.providers.filter { provider ->
            val providerId = catalog.providerId(provider)
            providerId in catalogState.loggedInProviderIds && providerId in catalogState.creatablePlaylistProviderIds
        }
    }

    override fun createProviderPlaylist(providerId: String, name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        scope.launch {
            publishLoading("正在新建歌单")
            runCatching { content.createPlaylist(providerId, normalized) }
                .onSuccess { result ->
                    if (result.success) {
                        finishLoading(result.message.ifBlank { "歌单已新建" })
                        refreshMinePlaylists()
                    } else {
                        finishLoading(result.message.ifBlank { "新建歌单失败" }, asError = true)
                    }
                }
                .onFailure(::publishError)
        }
    }

    override fun isFavoriteMinePlaylist(playlist: Playlist): Boolean {
        val key = content.playlistKey(playlist)
        return state.value.mineFavoritePlaylistSections.any { section ->
            content.contentPlaylists(section).any { content.playlistKey(it) == key }
        }
    }

    private fun refreshMineIfNeeded() {
        if (refreshNeeded.remove(HomeTopSection.Mine)) {
            refreshMine()
            return
        }
        when (state.value.mineSection) {
            HomeMineSection.Playlists -> if (
                state.value.minePlaylistSections.isEmpty() && state.value.mineFavoritePlaylistSections.isEmpty()
            ) {
                refreshMinePlaylists()
            }
            HomeMineSection.Artists, HomeMineSection.Albums -> if (state.value.mineSections.isEmpty()) {
                refreshMineContent()
            }
            HomeMineSection.LocalMusic -> localLibrary.ensureLocalMusic()
        }
    }

    private fun refreshHomeIfNeeded(section: HomeTopSection) {
        if (refreshNeeded.remove(section)) {
            refreshHome(section)
            return
        }
        val hasContent = when (section) {
            HomeTopSection.Recommend -> state.value.recommendSections.isNotEmpty()
            HomeTopSection.Explore -> state.value.exploreSections.isNotEmpty()
            HomeTopSection.Mine -> error("mine is loaded separately")
        }
        if (!hasContent) refreshHome(section)
    }

    private fun refreshMinePlaylists() {
        val serial = ++minePlaylistRefreshSerial
        scope.launch {
            publishLoading("正在加载我的歌单")
            val result = runCatching {
                val catalogState = catalog.state.value
                val selectedIds = selectedProviderIdsFor(HomeDisplaySurface.Mine, catalogState)
                val user = loadSectionsIncrementally(
                    features = catalogState.features.filter { feature ->
                        content.featureKind(feature) == HomeFeatureKind.MinePlaylists &&
                            content.featureProviderId(feature) in selectedIds
                    },
                    currentSections = state.value.minePlaylistSections,
                ) { sections ->
                    if (serial == minePlaylistRefreshSerial) {
                        mutableState.value = state.value.copy(minePlaylistSections = sections)
                    }
                }
                val favorite = loadSectionsIncrementally(
                    features = catalogState.features.filter { feature ->
                        content.featureKind(feature) == HomeFeatureKind.MineFavoritePlaylists &&
                            content.featureProviderId(feature) in selectedIds
                    },
                    currentSections = state.value.mineFavoritePlaylistSections,
                ) { sections ->
                    if (serial == minePlaylistRefreshSerial) {
                        mutableState.value = state.value.copy(mineFavoritePlaylistSections = sections)
                    }
                }
                localLibrary.refreshLocalPlaylists()
                user to favorite
            }
            if (serial == minePlaylistRefreshSerial) {
                result.onSuccess { (user, favorite) ->
                    finishLoading(if (user.isEmpty() && favorite.isEmpty()) "歌单暂无内容" else "歌单已更新")
                }.onFailure(::publishError)
            }
        }
    }

    private fun refreshMineContent() {
        val serial = ++mineContentRefreshSerial
        scope.launch {
            publishLoading("正在加载我的内容")
            val result = runCatching {
                val catalogState = catalog.state.value
                val selectedIds = selectedProviderIdsFor(HomeDisplaySurface.Mine, catalogState)
                loadSectionsIncrementally(
                    features = catalogState.features.filter { feature ->
                        content.featureKind(feature) == HomeFeatureKind.MineContent &&
                            content.featureProviderId(feature) in selectedIds
                    },
                    currentSections = state.value.mineSections,
                ) { sections ->
                    if (serial == mineContentRefreshSerial) {
                        mutableState.value = state.value.copy(mineSections = sections)
                    }
                }
            }
            if (serial == mineContentRefreshSerial) {
                result.onSuccess { sections ->
                    finishLoading(if (sections.isEmpty()) "我的内容暂无内容" else "我的内容已更新")
                }.onFailure(::publishError)
            }
        }
    }

    private suspend fun loadSectionsIncrementally(
        features: List<Feature>,
        currentSections: List<Content>,
        deferFeature: (Feature) -> Boolean = { false },
        onUpdate: (List<Content>) -> Unit,
    ): List<Content> {
        val catalogState = catalog.state.value
        val featureIds = features.mapTo(mutableSetOf(), content::featureId)
        var sections = sortSections(
            currentSections.filter { section -> content.featureId(content.contentFeature(section)) in featureIds },
            catalogState,
        )
        val loadingFeatures = mutableListOf<Feature>()
        features.forEach { feature ->
            val immediate = when {
                content.featureRequiresLogin(feature) && content.featureProviderId(feature) !in catalogState.loggedInProviderIds ->
                    content.loginRequiredContent(feature)
                deferFeature(feature) -> content.deferredContent(feature)
                else -> null
            }
            if (immediate == null) {
                loadingFeatures += feature
            } else {
                sections = mergeSection(sections, immediate, catalogState)
            }
        }
        onUpdate(sections)
        if (loadingFeatures.isEmpty()) return sections

        val updates = Channel<Content>(Channel.UNLIMITED)
        loadingFeatures.forEach { feature ->
            scope.launch {
                val loadedSection = runCatching {
                    withTimeout(HOME_PROVIDER_TIMEOUT_MS) { content.loadFeaturePage(feature, offset = 0) }
                }.getOrElse { throwable ->
                    content.errorContent(
                        feature,
                        content.errorMessage(throwable, content.featureProviderId(feature)),
                    )
                }
                updates.send(loadedSection)
            }
        }
        repeat(loadingFeatures.size) {
            sections = mergeSection(sections, updates.receive(), catalogState)
            onUpdate(sections)
        }
        updates.close()
        return sections
    }

    private fun mergeSection(
        sections: List<Content>,
        section: Content,
        catalogState: HomeCatalogSnapshot<Provider, Feature>,
    ): List<Content> = sortSections(
        sections.filterNot { existing ->
            content.featureId(content.contentFeature(existing)) == content.featureId(content.contentFeature(section))
        } + section,
        catalogState,
    )

    private fun sortSections(
        sections: List<Content>,
        catalogState: HomeCatalogSnapshot<Provider, Feature>,
    ): List<Content> {
        val providerOrder = catalogState.providers.mapIndexed { index, provider -> catalog.providerId(provider) to index }.toMap()
        val featureOrder = catalogState.features.mapIndexed { index, feature -> content.featureId(feature) to index }.toMap()
        val sorted = sections.sortedWith(
            compareBy<Content> { section ->
                providerOrder[content.featureProviderId(content.contentFeature(section))] ?: Int.MAX_VALUE
            }.thenBy { section ->
                featureOrder[content.featureId(content.contentFeature(section))] ?: Int.MAX_VALUE
            }.thenBy { section ->
                content.featureId(content.contentFeature(section))
            },
        )
        val contentTypes = sorted.map { section ->
            content.featureContentTypeKey(content.contentFeature(section))
        }.distinct()
        return contentTypes.flatMap { type ->
            sorted.filter { section -> content.featureContentTypeKey(content.contentFeature(section)) == type }
        }
    }

    private fun selectedProviderIdsFor(
        surface: HomeDisplaySurface,
        catalogState: HomeCatalogSnapshot<Provider, Feature>,
    ): Set<String> {
        val configured = catalogState.displayProviderIds[surface].orEmpty()
        return if (configured.isEmpty()) {
            catalogState.availableProviderIds.intersect(catalogState.enabledProviderIds)
        } else {
            configured.intersect(catalogState.enabledProviderIds)
        }
    }

    private fun loadDynamicFeatureAndPlay(feature: Feature) {
        scope.launch {
            publishLoading("正在加载${content.featureTitle(feature)}")
            runCatching {
                withTimeout(HOME_PROVIDER_TIMEOUT_MS) { content.loadFeaturePage(feature, offset = 0) }
            }.onSuccess { loadedSection ->
                updateHomeFeatureSection(loadedSection)
                val error = content.contentErrorMessage(loadedSection)
                val tracks = content.contentTracks(loadedSection)
                when {
                    !error.isNullOrBlank() -> finishLoading(error, asError = true)
                    tracks.isEmpty() -> finishLoading("暂无可播放歌曲")
                    else -> {
                        playback.playFeatureTracks(tracks, 0, feature)
                        finishLoading("正在播放${content.featureTitle(feature)}")
                    }
                }
            }.onFailure(::publishError)
        }
    }

    private fun updateHomeFeatureSection(section: Content) {
        val current = state.value
        val sectionId = content.featureId(content.contentFeature(section))
        fun replace(sections: List<Content>): List<Content> = sections.map { existing ->
            if (content.featureId(content.contentFeature(existing)) == sectionId) section else existing
        }
        mutableState.value = current.copy(
            recommendSections = replace(current.recommendSections),
            exploreSections = replace(current.exploreSections),
            mineSections = replace(current.mineSections),
            minePlaylistSections = replace(current.minePlaylistSections),
            mineFavoritePlaylistSections = replace(current.mineFavoritePlaylistSections),
        )
    }

    private fun publishLoading(message: String) {
        mutableState.value = state.value.copy(isLoading = true, message = message, errorMessage = null)
    }

    private fun finishLoading(message: String, asError: Boolean = false) {
        mutableState.value = state.value.copy(
            isLoading = false,
            message = message,
            errorMessage = message.takeIf { asError },
        )
    }

    private fun publishError(throwable: Throwable) {
        val message = content.errorMessage(throwable)
        mutableState.value = state.value.copy(isLoading = false, message = message, errorMessage = message)
    }
}

suspend fun <Feature, Content, Track, Playlist> loadAllHomeTracks(
    initial: Content,
    content: HomeContentPort<Feature, Content, Track, Playlist>,
): List<Track> {
    val tracks = content.contentTracks(initial).toMutableList()
    val seenIds = tracks.mapTo(mutableSetOf(), content::trackKey)
    val feature = content.contentFeature(initial)
    var hasMore = content.contentHasMore(initial)
    var nextOffset = content.contentNextOffset(initial).takeIf { it > 0 } ?: tracks.size

    while (hasMore) {
        val requestedOffset = nextOffset
        val page = withTimeout(HOME_PROVIDER_TIMEOUT_MS) {
            content.loadFeaturePage(feature, requestedOffset)
        }
        content.contentTracks(page).forEach { track ->
            if (seenIds.add(content.trackKey(track))) tracks += track
        }
        hasMore = content.contentHasMore(page)
        if (!hasMore) break

        val pageTracks = content.contentTracks(page)
        val candidateOffset = content.contentNextOffset(page).takeIf { it > requestedOffset }
            ?: (requestedOffset + pageTracks.size)
        if (candidateOffset <= requestedOffset || pageTracks.isEmpty()) break
        nextOffset = candidateOffset
    }
    return tracks
}
