package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val HomePrimarySections = listOf(
    HomeSection.Recommend to "推荐",
    HomeSection.Music to "探索",
    HomeSection.Mine to "我的",
)

private val HomeNavigationScrollThreshold = 28.dp
private val HomeNavigationItemSpacing = 4.dp
private val HomeRailCompactHeightThreshold = 400.dp
private val HomeRailRecognitionHeightThreshold = 320.dp
private val HomeRailExpandedItemHeight = 64.dp
private val HomeRailCompactItemHeight = 48.dp

@Composable
fun LoadingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

@Composable
fun HomeScreen(
    home: HomeFeatureController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val playbackUiPort = LocalPlaybackUiPort.current
    val state = home.uiState.collectAsStateWithLifecycle().value
    val currentHomeSection by rememberUpdatedState(state.homeSection)
    val selectedIndex = HomePrimarySections.indexOfFirst { it.first == state.homeSection }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { HomePrimarySections.size },
    )
    val scope = rememberCoroutineScope()
    var isNavigationCompact by remember { mutableStateOf(false) }
    val scrollThresholdPx = with(LocalDensity.current) { HomeNavigationScrollThreshold.toPx() }
    val navigationScrollAccumulator = remember(scrollThresholdPx) {
        HomeNavigationScrollAccumulator(scrollThresholdPx)
    }
    val navigationScrollConnection = remember(navigationScrollAccumulator) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                navigationScrollAccumulator.onScroll(available.y)?.let { compact ->
                    isNavigationCompact = compact
                }
                return Offset.Zero
            }
        }
    }
    val selectSection: (Int, HomeSection) -> Unit = { index, section ->
        if (section != state.homeSection || index != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(index) }
        }
    }

    LaunchedEffect(state.homeSection) {
        val page = HomePrimarySections.indexOfFirst { it.first == state.homeSection }
        if (page >= 0 && page != pagerState.currentPage) pagerState.animateScrollToPage(page)
        navigationScrollAccumulator.reset()
        isNavigationCompact = false
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                HomePrimarySections.getOrNull(page)?.first?.let { section ->
                    if (section != currentHomeSection) home.setHomeSection(section)
                }
            }
    }

    Scaffold(
        topBar = {
            if (!layoutInfo.useWideLayout) {
                ExpressiveHomeTopBar(
                    sections = HomePrimarySections,
                    pagerState = pagerState,
                    compact = isNavigationCompact,
                    onSettings = home::openSettings,
                    onRecognition = onOpenRecognition,
                    onSearch = home::openSearch,
                    onSectionClick = selectSection,
                )
            }
        },
        bottomBar = {
            if (playbackUiPort.currentTrack != null) PlaybackMiniPlayer()
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(if (layoutInfo.useWideLayout) 6.dp else 8.dp),
        ) {
            LoadingIndicator(
                visible = state.isLoading,
                modifier = Modifier.padding(horizontal = if (layoutInfo.useWideLayout) 8.dp else 16.dp),
            )
            HomeSectionPager(
                home = home,
                sections = HomePrimarySections,
                pagerState = pagerState,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                hasImagePermission = hasImagePermission,
                onRequestImagePermission = onRequestImagePermission,
                onOpenRecognition = onOpenRecognition,
                onSectionClick = selectSection,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (layoutInfo.useWideLayout) Modifier
                        else Modifier.nestedScroll(navigationScrollConnection),
                    ),
                contentHorizontalPadding = if (layoutInfo.useWideLayout) 8.dp else 16.dp,
            )
        }
    }
}

@Composable
private fun ExpressiveHomeTopBar(
    sections: List<Pair<HomeSection, String>>,
    pagerState: PagerState,
    compact: Boolean,
    onSettings: () -> Unit,
    onRecognition: () -> Unit,
    onSearch: () -> Unit,
    onSectionClick: (Int, HomeSection) -> Unit,
) {
    val verticalPadding by animateDpAsState(
        targetValue = if (compact) 4.dp else 10.dp,
        animationSpec = tween(FuoMotion.overlayEnterMillis),
        label = "home navigation vertical padding",
    )
    val itemHeight by animateDpAsState(
        targetValue = if (compact) 40.dp else 52.dp,
        animationSpec = tween(FuoMotion.overlayEnterMillis),
        label = "home navigation item height",
    )
    val elevation by animateDpAsState(
        targetValue = if (compact) 2.dp else 0.dp,
        animationSpec = tween(FuoMotion.overlayFadeMillis),
        label = "home navigation elevation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (compact) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(FuoMotion.overlayFadeMillis),
        label = "home navigation container color",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = elevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeNavigationAction(
                compact = compact,
                onClick = onSettings,
                contentDescription = "设置",
                icon = Icons.Filled.Settings,
            )
            HomeNavigationTabs(
                modifier = Modifier.weight(1f),
                sections = sections,
                pagerState = pagerState,
                compact = compact,
                height = itemHeight,
                onSectionClick = onSectionClick,
            )
            HomeNavigationAction(
                compact = compact,
                onClick = onRecognition,
                contentDescription = "听歌识曲",
                icon = Icons.Filled.Mic,
            )
            HomeNavigationAction(
                compact = compact,
                onClick = onSearch,
                contentDescription = "搜索",
                icon = Icons.Filled.Search,
            )
        }
    }
}

@Composable
private fun HomeNavigationTabs(
    sections: List<Pair<HomeSection, String>>,
    pagerState: PagerState,
    compact: Boolean,
    height: Dp,
    onSectionClick: (Int, HomeSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerPosition = (
        pagerState.currentPage.toFloat() + pagerState.currentPageOffsetFraction
        ).coerceIn(0f, sections.lastIndex.toFloat())
    val selectedIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex)
    val selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
    val selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier = modifier.height(height)) {
        val itemWidth = (
            maxWidth - HomeNavigationItemSpacing * sections.lastIndex.toFloat()
            ) / sections.size.toFloat()
        val indicatorOffset = (itemWidth + HomeNavigationItemSpacing) * pagerPosition
        Surface(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            color = selectedContainerColor,
        ) {}
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(HomeNavigationItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sections.forEachIndexed { index, (section, label) ->
                val emphasis = (1f - (pagerPosition - index).absoluteValue).coerceIn(0f, 1f)
                HomeNavigationItem(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = label,
                    selected = index == selectedIndex,
                    compact = compact,
                    contentColor = lerp(unselectedContentColor, selectedContentColor, emphasis),
                    onClick = { onSectionClick(index, section) },
                )
            }
        }
    }
}

@Composable
private fun HomeNavigationItem(
    label: String,
    selected: Boolean,
    compact: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeNavigationAction(
    compact: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
) {
    if (compact) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        FilledTonalIconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

internal class HomeNavigationScrollAccumulator(
    private val thresholdPx: Float,
) {
    private var accumulatedY = 0f

    init {
        require(thresholdPx > 0f)
    }

    fun onScroll(deltaY: Float): Boolean? {
        if (deltaY == 0f) return null
        if ((deltaY > 0f && accumulatedY < 0f) || (deltaY < 0f && accumulatedY > 0f)) {
            accumulatedY = 0f
        }
        accumulatedY += deltaY
        return when {
            accumulatedY <= -thresholdPx -> {
                accumulatedY = 0f
                true
            }
            accumulatedY >= thresholdPx -> {
                accumulatedY = 0f
                false
            }
            else -> null
        }
    }

    fun reset() {
        accumulatedY = 0f
    }
}

internal data class HomeRailLayoutPolicy(
    val showLabels: Boolean,
    val showRecognition: Boolean,
)

internal fun homeRailLayoutPolicyFor(maxHeight: Dp): HomeRailLayoutPolicy = HomeRailLayoutPolicy(
    showLabels = maxHeight >= HomeRailCompactHeightThreshold,
    showRecognition = maxHeight >= HomeRailRecognitionHeightThreshold,
)

@Composable
fun HomeSectionPager(
    home: HomeFeatureController,
    sections: List<Pair<HomeSection, String>>,
    pagerState: PagerState,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    onOpenRecognition: () -> Unit,
    onSectionClick: (Int, HomeSection) -> Unit,
    modifier: Modifier,
    contentHorizontalPadding: Dp,
) {
    if (LocalAppLayoutInfo.current.useWideLayout) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = contentHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeSectionRail(
                sections = sections,
                selectedIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex),
                onSettings = home::openSettings,
                onSearch = home::openSearch,
                onRecognition = onOpenRecognition,
                onClick = onSectionClick,
            )
            HomeSectionPages(
                home = home,
                sections = sections,
                pagerState = pagerState,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                hasImagePermission = hasImagePermission,
                onRequestImagePermission = onRequestImagePermission,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        return
    }

    HomeSectionPages(
        home = home,
        sections = sections,
        pagerState = pagerState,
        hasAudioPermission = hasAudioPermission,
        onRequestAudioPermission = onRequestAudioPermission,
        hasImagePermission = hasImagePermission,
        onRequestImagePermission = onRequestImagePermission,
        modifier = modifier.fillMaxWidth().padding(horizontal = contentHorizontalPadding),
    )
}

@Composable
private fun HomeSectionPages(
    home: HomeFeatureController,
    sections: List<Pair<HomeSection, String>>,
    pagerState: PagerState,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    modifier: Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        pageSpacing = 16.dp,
    ) { page ->
        when (sections[page].first) {
            HomeSection.Recommend -> ProviderContentHomeSection(home, HomeSection.Recommend, Modifier.fillMaxSize())
            HomeSection.Music -> ProviderContentHomeSection(home, HomeSection.Music, Modifier.fillMaxSize())
            HomeSection.Mine -> MineHomeSection(
                home = home,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                hasImagePermission = hasImagePermission,
                onRequestImagePermission = onRequestImagePermission,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun HomeSectionRail(
    sections: List<Pair<HomeSection, String>>,
    selectedIndex: Int,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onRecognition: () -> Unit,
    onClick: (Int, HomeSection) -> Unit,
) {
    Surface(
        modifier = Modifier.width(64.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val policy = homeRailLayoutPolicyFor(maxHeight)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = if (policy.showLabels) 8.dp else 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
                Spacer(Modifier.weight(1f))
                sections.forEachIndexed { index, (section, label) ->
                    HomeSectionRailItem(
                        section = section,
                        label = label,
                        selected = index == selectedIndex,
                        showLabel = policy.showLabels,
                        onClick = { onClick(index, section) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (policy.showRecognition) {
                    IconButton(onClick = onRecognition) {
                        Icon(Icons.Filled.Mic, contentDescription = "听歌识曲")
                    }
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRailItem(
    section: HomeSection,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showLabel) HomeRailExpandedItemHeight else HomeRailCompactItemHeight)
            .fuoInteractive()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(homeSectionIcon(section), contentDescription = if (showLabel) null else label)
            if (showLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun homeSectionIcon(section: HomeSection): ImageVector = when (section) {
    HomeSection.Recommend -> Icons.Filled.PlayArrow
    HomeSection.Music -> Icons.Filled.Album
    HomeSection.Mine -> Icons.Filled.Person
}

@Composable
fun EmptyHomeSection(modifier: Modifier, title: String) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
