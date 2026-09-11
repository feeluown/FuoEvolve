package org.feeluown.mobile.feature.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeatureTest {
    @Test
    fun initialContentWaitsForPreferencesAndCatalogReadiness() = runTest {
        val feature = FakeFeature("recommend", "p1", HomeFeatureKind.Recommend)
        val preferences = FakePreferences(loaded = false)
        val catalog = FakeCatalog(
            HomeCatalogSnapshot(
                isInitialized = false,
                providers = listOf(FakeProvider("p1")),
                features = listOf(feature),
                availableProviderIds = setOf("p1"),
                enabledProviderIds = setOf("p1"),
            ),
        )
        val content = FakeContentPort().apply {
            pages[feature.id to 0] = FakeContent(feature, tracks = listOf(FakeTrack("1")))
        }
        val owner = owner(backgroundScope, preferences, catalog, content)

        runCurrent()
        assertTrue(content.loads.isEmpty())

        preferences.mutableState.value = preferences.mutableState.value.copy(isLoaded = true)
        runCurrent()
        assertTrue(content.loads.isEmpty())

        catalog.mutableState.value = catalog.mutableState.value.copy(isInitialized = true)
        runCurrent()

        assertEquals(listOf("recommend" to 0), content.loads)
        assertEquals(listOf("1"), owner.state.value.recommendSections.single().tracks.map { it.id })
    }

    @Test
    fun loginRequiredFeaturePublishesPlaceholderWithoutProviderRequest() = runTest {
        val feature = FakeFeature(
            id = "mine",
            providerId = "p1",
            kind = HomeFeatureKind.Recommend,
            requiresLogin = true,
        )
        val preferences = FakePreferences(loaded = true)
        val catalog = FakeCatalog(
            readyCatalog(
                providers = listOf(FakeProvider("p1")),
                features = listOf(feature),
                loggedInProviderIds = emptySet(),
            ),
        )
        val content = FakeContentPort()
        val owner = owner(backgroundScope, preferences, catalog, content)

        runCurrent()

        assertTrue(content.loads.isEmpty())
        assertTrue(owner.state.value.recommendSections.single().loginRequired)
    }

    @Test
    fun markedRefreshWaitsUntilCurrentSectionIsEntered() = runTest {
        val feature = FakeFeature("recommend", "p1", HomeFeatureKind.Recommend)
        val preferences = FakePreferences(loaded = true)
        val catalog = FakeCatalog(
            readyCatalog(
                providers = listOf(FakeProvider("p1")),
                features = listOf(feature),
            ),
        )
        val content = FakeContentPort().apply {
            pages[feature.id to 0] = FakeContent(feature, tracks = listOf(FakeTrack("1")))
        }
        val owner = owner(backgroundScope, preferences, catalog, content)
        runCurrent()

        content.loads.clear()
        owner.markRefreshNeeded(HomeTopSection.Recommend)
        runCurrent()
        assertTrue(content.loads.isEmpty())

        owner.refreshCurrentSectionIfNeeded()
        runCurrent()
        assertEquals(listOf("recommend" to 0), content.loads)
    }

    @Test
    fun mineLocalMusicUsesNarrowEnsurePortOnInitialLoad() = runTest {
        val preferences = FakePreferences(
            loaded = true,
            homeSection = HomeTopSection.Mine,
            mineSection = HomeMineSection.LocalMusic,
        )
        val catalog = FakeCatalog(readyCatalog())
        val localLibrary = FakeLocalLibrary()

        owner(backgroundScope, preferences, catalog, FakeContentPort(), localLibrary = localLibrary)
        runCurrent()

        assertEquals(1, localLibrary.ensureMusicCalls)
        assertEquals(0, localLibrary.refreshMusicCalls)
    }

    @Test
    fun playAllLoadsEveryPageAndDeduplicatesTracks() = runTest {
        val feature = FakeFeature("recommend", "p1", HomeFeatureKind.Recommend)
        val content = FakeContentPort().apply {
            pages[feature.id to 2] = FakeContent(
                feature = feature,
                tracks = listOf(FakeTrack("2"), FakeTrack("3")),
                nextOffset = 4,
                hasMore = true,
            )
            pages[feature.id to 4] = FakeContent(
                feature = feature,
                tracks = listOf(FakeTrack("4")),
                nextOffset = 5,
                hasMore = false,
            )
        }

        val result = loadAllHomeTracks(
            initial = FakeContent(
                feature = feature,
                tracks = listOf(FakeTrack("1"), FakeTrack("2")),
                nextOffset = 2,
                hasMore = true,
            ),
            content = content,
        )

        assertEquals(listOf("1", "2", "3", "4"), result.map { it.id })
        assertEquals(listOf("recommend" to 2, "recommend" to 4), content.loads)
    }

    @Test
    fun playAllTimesOutWhenFollowUpPageDoesNotReturn() = runTest {
        val feature = FakeFeature("recommend", "p1", HomeFeatureKind.Recommend)
        val preferences = FakePreferences(loaded = false)
        val catalog = FakeCatalog(readyCatalog())
        val content = FakeContentPort().apply {
            hangingPages += feature.id to 1
        }
        val playback = FakePlayback()
        val owner = owner(backgroundScope, preferences, catalog, content, playback = playback)

        owner.playAllFeature(
            FakeContent(
                feature = feature,
                tracks = listOf(FakeTrack("1")),
                nextOffset = 1,
                hasMore = true,
            ),
        )
        runCurrent()

        assertTrue(owner.state.value.isLoading)
        assertEquals(listOf("recommend" to 1), content.loads)

        advanceTimeBy(30_001)
        runCurrent()

        assertFalse(owner.state.value.isLoading)
        assertTrue(owner.state.value.errorMessage != null)
        assertTrue(playback.calls.isEmpty())
    }

    @Test
    fun emptyDynamicFeatureLoadsBeforePlayback() = runTest {
        val feature = FakeFeature(
            id = "fm",
            providerId = "p1",
            kind = HomeFeatureKind.Recommend,
            dynamic = true,
        )
        val preferences = FakePreferences(loaded = false)
        val catalog = FakeCatalog(readyCatalog())
        val content = FakeContentPort().apply {
            pages[feature.id to 0] = FakeContent(feature, tracks = listOf(FakeTrack("fm-1")))
        }
        val playback = FakePlayback()
        val owner = owner(backgroundScope, preferences, catalog, content, playback = playback)

        owner.playAllFeature(FakeContent(feature))
        runCurrent()

        assertEquals(listOf("fm" to 0), content.loads)
        assertEquals(listOf(PlaybackCall("fm", listOf("fm-1"), 0)), playback.calls)
    }

    @Test
    fun creatablePlaylistProvidersRequireLoginAndCapability() = runTest {
        val preferences = FakePreferences(loaded = false)
        val providers = listOf(FakeProvider("a"), FakeProvider("b"), FakeProvider("c"))
        val catalog = FakeCatalog(
            readyCatalog(
                providers = providers,
                loggedInProviderIds = setOf("a", "b"),
                creatablePlaylistProviderIds = setOf("a", "c"),
            ),
        )
        val owner = owner(backgroundScope, preferences, catalog, FakeContentPort())

        assertEquals(listOf("a"), owner.creatablePlaylistProviders().map { it.id })
    }

    @Test
    fun successfulPlaylistCreationRefreshesMinePlaylists() = runTest {
        val preferences = FakePreferences(
            loaded = false,
            homeSection = HomeTopSection.Mine,
            mineSection = HomeMineSection.Playlists,
        )
        val catalog = FakeCatalog(readyCatalog())
        val content = FakeContentPort().apply {
            createResult = HomeMutationResult(success = true, message = "created")
        }
        val localLibrary = FakeLocalLibrary()
        val owner = owner(backgroundScope, preferences, catalog, content, localLibrary = localLibrary)

        owner.createProviderPlaylist("p1", "  New playlist  ")
        runCurrent()

        assertEquals(listOf("p1" to "New playlist"), content.createdPlaylists)
        assertEquals(1, localLibrary.refreshPlaylistCalls)
        assertFalse(owner.state.value.isLoading)
        assertEquals("歌单暂无内容", owner.state.value.message)
    }

    private fun owner(
        scope: CoroutineScope,
        preferences: FakePreferences,
        catalog: FakeCatalog,
        content: FakeContentPort,
        playback: FakePlayback = FakePlayback(),
        localLibrary: FakeLocalLibrary = FakeLocalLibrary(),
    ): HomeFeatureOwner<FakeProvider, FakeFeature, FakeContent, FakeTrack, FakePlaylist, Unit> =
        createHomeFeatureOwner(
            preferences = preferences,
            catalog = catalog,
            content = content,
            playback = playback,
            localLibrary = localLibrary,
            scope = scope,
        )

    private fun readyCatalog(
        providers: List<FakeProvider> = emptyList(),
        features: List<FakeFeature> = emptyList(),
        loggedInProviderIds: Set<String> = emptySet(),
        creatablePlaylistProviderIds: Set<String> = emptySet(),
    ): HomeCatalogSnapshot<FakeProvider, FakeFeature> = HomeCatalogSnapshot(
        isInitialized = true,
        providers = providers,
        features = features,
        availableProviderIds = providers.mapTo(linkedSetOf()) { it.id },
        enabledProviderIds = providers.mapTo(linkedSetOf()) { it.id },
        loggedInProviderIds = loggedInProviderIds,
        creatablePlaylistProviderIds = creatablePlaylistProviderIds,
    )
}

private data class FakeProvider(val id: String)

private data class FakeFeature(
    val id: String,
    val providerId: String,
    val kind: HomeFeatureKind,
    val requiresLogin: Boolean = false,
    val contentType: String = "songs",
    val deferred: Boolean = false,
    val dynamic: Boolean = false,
)

private data class FakeTrack(val id: String)
private data class FakePlaylist(val id: String, val providerId: String = "p1")

private data class FakeContent(
    val feature: FakeFeature,
    val tracks: List<FakeTrack> = emptyList(),
    val playlists: List<FakePlaylist> = emptyList(),
    val loginRequired: Boolean = false,
    val error: String? = null,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
)

private class FakePreferences(
    loaded: Boolean,
    homeSection: HomeTopSection = HomeTopSection.Recommend,
    mineSection: HomeMineSection = HomeMineSection.Playlists,
    playlistFilter: HomePlaylistFilter = HomePlaylistFilter.UserPlaylists,
) : HomePreferencesPort<Unit> {
    val mutableState: MutableStateFlow<HomePreferencesSnapshot<Unit>> = MutableStateFlow(
        HomePreferencesSnapshot(
            isLoaded = loaded,
            homeSection = homeSection,
            mineSection = mineSection,
            playlistFilter = playlistFilter,
            playlistPlaybackStats = Unit,
        ),
    )
    override val state: StateFlow<HomePreferencesSnapshot<Unit>> = mutableState

    override suspend fun setHomeSection(section: HomeTopSection) {
        mutableState.value = mutableState.value.copy(homeSection = section)
    }

    override suspend fun setMineSection(section: HomeMineSection) {
        mutableState.value = mutableState.value.copy(mineSection = section)
    }

    override suspend fun setPlaylistFilter(filter: HomePlaylistFilter) {
        mutableState.value = mutableState.value.copy(playlistFilter = filter)
    }
}

private class FakeCatalog(
    initial: HomeCatalogSnapshot<FakeProvider, FakeFeature>,
) : HomeCatalogPort<FakeProvider, FakeFeature> {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<HomeCatalogSnapshot<FakeProvider, FakeFeature>> = mutableState
    override fun providerId(provider: FakeProvider): String = provider.id
}

private class FakeContentPort : HomeContentPort<FakeFeature, FakeContent, FakeTrack, FakePlaylist> {
    val pages = mutableMapOf<Pair<String, Int>, FakeContent>()
    val hangingPages = mutableSetOf<Pair<String, Int>>()
    val loads = mutableListOf<Pair<String, Int>>()
    val createdPlaylists = mutableListOf<Pair<String, String>>()
    var createResult = HomeMutationResult(success = true, message = "created")

    override suspend fun loadFeaturePage(feature: FakeFeature, offset: Int): FakeContent {
        val key = feature.id to offset
        loads += key
        if (key in hangingPages) awaitCancellation()
        return pages[key] ?: FakeContent(feature)
    }

    override suspend fun createPlaylist(providerId: String, name: String): HomeMutationResult {
        createdPlaylists += providerId to name
        return createResult
    }

    override fun featureId(feature: FakeFeature): String = feature.id
    override fun featureProviderId(feature: FakeFeature): String = feature.providerId
    override fun featureTitle(feature: FakeFeature): String = feature.id
    override fun featureKind(feature: FakeFeature): HomeFeatureKind = feature.kind
    override fun featureRequiresLogin(feature: FakeFeature): Boolean = feature.requiresLogin
    override fun featureContentTypeKey(feature: FakeFeature): String = feature.contentType
    override fun isDeferredFeature(feature: FakeFeature): Boolean = feature.deferred
    override fun isDynamicQueueFeature(feature: FakeFeature): Boolean = feature.dynamic
    override fun contentFeature(content: FakeContent): FakeFeature = content.feature
    override fun contentTracks(content: FakeContent): List<FakeTrack> = content.tracks
    override fun contentPlaylists(content: FakeContent): List<FakePlaylist> = content.playlists
    override fun contentNextOffset(content: FakeContent): Int = content.nextOffset
    override fun contentHasMore(content: FakeContent): Boolean = content.hasMore
    override fun contentErrorMessage(content: FakeContent): String? = content.error
    override fun loginRequiredContent(feature: FakeFeature): FakeContent = FakeContent(feature, loginRequired = true)
    override fun deferredContent(feature: FakeFeature): FakeContent = FakeContent(feature)
    override fun errorContent(feature: FakeFeature, message: String): FakeContent = FakeContent(feature, error = message)
    override fun trackKey(track: FakeTrack): String = track.id
    override fun playlistKey(playlist: FakePlaylist): String = "${playlist.providerId}::${playlist.id}"
    override fun errorMessage(throwable: Throwable, providerId: String?): String = throwable.message ?: "failed"
}

private data class PlaybackCall(
    val featureId: String,
    val trackIds: List<String>,
    val index: Int,
)

private class FakePlayback : HomePlaybackPort<FakeFeature, FakeTrack> {
    val calls = mutableListOf<PlaybackCall>()
    override fun playFeatureTracks(tracks: List<FakeTrack>, index: Int, feature: FakeFeature) {
        calls += PlaybackCall(feature.id, tracks.map { it.id }, index)
    }
}

private class FakeLocalLibrary : HomeLocalLibraryPort {
    var refreshPlaylistCalls = 0
    var refreshMusicCalls = 0
    var ensureMusicCalls = 0

    override fun refreshLocalPlaylists() {
        refreshPlaylistCalls += 1
    }

    override fun refreshLocalMusic() {
        refreshMusicCalls += 1
    }

    override fun ensureLocalMusic() {
        ensureMusicCalls += 1
    }
}
