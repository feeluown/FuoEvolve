package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ProviderContentController(
    private val providerRepository: ProviderMusicRepository,
    private val state: ProviderControllerState,
    private val localPlaylistController: LocalPlaylistController,
    private val scope: CoroutineScope,
    private val mineSection: () -> MineSection,
    private val selectedProviderIdsFor: (ProviderDisplaySection) -> Set<String>,
    private val isProviderLoggedIn: (String) -> Boolean,
    private val refreshProviderCatalog: suspend () -> Unit,
    private val ensureLocalMusic: () -> Unit,
    private val sortSections: (List<ProviderContentSection>) -> List<ProviderContentSection>,
    private val isDeferredHomeFeature: (ProviderFeature) -> Boolean,
    private val providerErrorMessage: (Throwable, String, String?) -> String,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var recommendContentRefreshSerial: Long = 0
    private var musicContentRefreshSerial: Long = 0
    private var minePlaylistRefreshSerial: Long = 0
    private var mineContentRefreshSerial: Long = 0

    fun refreshHomeContent(section: HomeSection, refreshCatalog: Boolean = true) {
        if (section == HomeSection.Mine) {
            refreshActiveMineSection(refreshCatalog)
            return
        }
        val refreshSerial = when (section) {
            HomeSection.Recommend -> ++recommendContentRefreshSerial
            HomeSection.Music -> ++musicContentRefreshSerial
            HomeSection.Mine -> error("mine section is loaded separately")
        }
        fun isCurrentRefresh(): Boolean = when (section) {
            HomeSection.Recommend -> refreshSerial == recommendContentRefreshSerial
            HomeSection.Music -> refreshSerial == musicContentRefreshSerial
            HomeSection.Mine -> false
        }
        scope.launch {
            if (isCurrentRefresh()) setLoading(true)
            val title = if (section == HomeSection.Recommend) "推荐" else "探索"
            if (isCurrentRefresh()) setMessage("正在加载$title")
            val result = runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                val category = when (section) {
                    HomeSection.Recommend -> ProviderFeatureCategory.Recommend
                    HomeSection.Music -> ProviderFeatureCategory.Music
                    HomeSection.Mine -> error("mine section is loaded separately")
                }
                val displaySection = if (section == HomeSection.Recommend) {
                    ProviderDisplaySection.Recommend
                } else {
                    ProviderDisplaySection.Explore
                }
                val currentSections = if (section == HomeSection.Recommend) {
                    state.recommendSections
                } else {
                    state.musicSections
                }
                loadProviderSectionsIncrementally(
                    category = category,
                    includeFeature = { it.providerId in selectedProviderIdsFor(displaySection) },
                    currentSections = currentSections,
                    deferFeature = isDeferredHomeFeature,
                ) { sections ->
                    if (isCurrentRefresh()) {
                        if (section == HomeSection.Recommend) {
                            state.recommendSections = sections
                        } else {
                            state.musicSections = sections
                        }
                    }
                }
            }
            if (isCurrentRefresh()) {
                result
                    .onSuccess { sections ->
                        setMessage(if (sections.isEmpty()) "$title 暂无内容" else "$title 已更新")
                    }
                    .onFailure(onError)
                setLoading(false)
            }
        }
    }

    fun refreshMinePlaylistContent(refreshCatalog: Boolean = true) {
        val refreshSerial = ++minePlaylistRefreshSerial
        fun isCurrentRefresh(): Boolean = refreshSerial == minePlaylistRefreshSerial
        scope.launch {
            if (isCurrentRefresh()) {
                setLoading(true)
                setMessage("正在加载我的歌单")
            }
            val result = runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                val userPlaylistsDeferred = async {
                    loadProviderSectionsIncrementally(
                        category = ProviderFeatureCategory.MinePlaylists,
                        includeFeature = ::isMineProviderFeature,
                        currentSections = state.minePlaylistSections,
                    ) { sections ->
                        if (isCurrentRefresh()) state.minePlaylistSections = sections
                    }
                }
                val favoritePlaylistsDeferred = async {
                    loadProviderSectionsIncrementally(
                        category = ProviderFeatureCategory.MineFavoritePlaylists,
                        includeFeature = ::isMineProviderFeature,
                        currentSections = state.mineFavoritePlaylistSections,
                    ) { sections ->
                        if (isCurrentRefresh()) state.mineFavoritePlaylistSections = sections
                    }
                }
                val localDeferred = async {
                    localPlaylistController.loadForContent()
                }
                Triple(
                    userPlaylistsDeferred.await(),
                    favoritePlaylistsDeferred.await(),
                    localDeferred.await(),
                )
            }
            if (isCurrentRefresh()) {
                result
                    .onSuccess {
                        setMessage(
                            if (it.first.isEmpty() && it.second.isEmpty() && it.third.isEmpty()) {
                                "歌单暂无内容"
                            } else {
                                "歌单已更新"
                            }
                        )
                    }
                    .onFailure(onError)
                setLoading(false)
            }
        }
    }

    fun refreshMineContent(refreshCatalog: Boolean = true) {
        val refreshSerial = ++mineContentRefreshSerial
        fun isCurrentRefresh(): Boolean = refreshSerial == mineContentRefreshSerial
        scope.launch {
            if (isCurrentRefresh()) {
                setLoading(true)
                setMessage("正在加载我的内容")
            }
            val result = runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                loadProviderSectionsIncrementally(
                    category = ProviderFeatureCategory.Mine,
                    includeFeature = ::isMineProviderFeature,
                    currentSections = state.mineSections,
                ) { sections ->
                    if (isCurrentRefresh()) state.mineSections = sections
                }
            }
            if (isCurrentRefresh()) {
                result
                    .onSuccess { sections ->
                        setMessage(if (sections.isEmpty()) "我的内容暂无内容" else "我的内容已更新")
                    }
                    .onFailure(onError)
                setLoading(false)
            }
        }
    }

    fun refreshActiveMineSectionIfNeeded() {
        when (mineSection()) {
            MineSection.Playlists -> if (state.minePlaylistSections.isEmpty() && state.mineFavoritePlaylistSections.isEmpty()) {
                refreshMinePlaylistContent()
            }
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> if (state.mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    fun refreshActiveMineSection(refreshCatalog: Boolean = true) {
        when (mineSection()) {
            MineSection.Playlists -> refreshMinePlaylistContent(refreshCatalog)
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent(refreshCatalog)
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    fun refreshActiveMineProviderContent() {
        when (mineSection()) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent()
            MineSection.LocalMusic -> Unit
        }
    }

    private suspend fun loadProviderSectionsIncrementally(
        category: ProviderFeatureCategory,
        includeFeature: (ProviderFeature) -> Boolean = { true },
        currentSections: List<ProviderContentSection> = emptyList(),
        deferFeature: (ProviderFeature) -> Boolean = { false },
        onUpdate: (List<ProviderContentSection>) -> Unit,
    ): List<ProviderContentSection> {
        val features = state.features.filter { it.category == category && includeFeature(it) }
        val featureIds = features.mapTo(mutableSetOf()) { it.id }
        var sections = sortSections(currentSections.filter { it.feature.id in featureIds })
        val loadingFeatures = mutableListOf<ProviderFeature>()
        features.forEach { feature ->
            val immediateSection = when {
                feature.requiresLogin && !isProviderLoggedIn(feature.providerId) ->
                    ProviderContentSection(feature, isLoginRequired = true)
                deferFeature(feature) -> ProviderContentSection(feature)
                else -> null
            }
            if (immediateSection != null) {
                sections = mergeLoadedSection(sections, immediateSection)
            } else {
                loadingFeatures += feature
            }
        }
        onUpdate(sections)
        if (loadingFeatures.isEmpty()) return sections

        val updates = Channel<ProviderContentSection>(capacity = Channel.UNLIMITED)
        loadingFeatures.forEach { feature ->
            scope.launch {
                val loaded = runCatching {
                    withTimeout(30_000) {
                        providerRepository.loadFeaturePage(feature, offset = 0)
                    }
                }.getOrElse { throwable ->
                    ProviderContentSection(
                        feature = feature,
                        errorMessage = providerErrorMessage(throwable, "加载失败", feature.providerId),
                    )
                }
                updates.send(loaded)
            }
        }
        repeat(loadingFeatures.size) {
            sections = mergeLoadedSection(sections, updates.receive())
            onUpdate(sections)
        }
        updates.close()
        return sections
    }

    private fun mergeLoadedSection(
        sections: List<ProviderContentSection>,
        section: ProviderContentSection,
    ): List<ProviderContentSection> =
        sortSections(sections.filterNot { it.feature.id == section.feature.id } + section)

    private fun isMineProviderFeature(feature: ProviderFeature): Boolean =
        feature.providerId in selectedProviderIdsFor(ProviderDisplaySection.Mine)
}
