package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun LoadingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    Scaffold(
        topBar = {
            if (!layoutInfo.useWideLayout) {
                CenterAlignedTopAppBar(
                    title = { Text("FeelUOwn") },
                    navigationIcon = {
                        IconButton(onClick = { controller.openSettings() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenRecognition) {
                            Icon(Icons.Filled.Mic, contentDescription = "听歌识曲")
                        }
                        IconButton(onClick = controller::openSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        bottomBar = {
            if (controller.playbackState.currentTrack != null) {
                MiniPlayer(controller)
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(if (layoutInfo.useWideLayout) 6.dp else 12.dp),
        ) {
            LoadingIndicator(
                visible = controller.isLoading,
                modifier = Modifier.padding(horizontal = if (layoutInfo.useWideLayout) 8.dp else 16.dp),
            )
            HomeSectionPager(
                controller = controller,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                onOpenRecognition = onOpenRecognition,
                modifier = Modifier.weight(1f),
                contentHorizontalPadding = if (layoutInfo.useWideLayout) 8.dp else 16.dp,
            )
        }
    }
}

@Composable
fun HomeSectionPager(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenRecognition: () -> Unit,
    modifier: Modifier,
    contentHorizontalPadding: Dp,
) {
    val sections = listOf(
        HomeSection.Recommend to "推荐",
        HomeSection.Music to "探索",
        HomeSection.Mine to "我的",
    )
    val selectedIndex = sections.indexOfFirst { it.first == controller.homeSection }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { sections.size },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller.homeSection) {
        val page = sections.indexOfFirst { it.first == controller.homeSection }
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val section = sections.getOrNull(page)?.first
                if (section != null && section != controller.homeSection) {
                    controller.onHomeSectionChange(section)
                }
            }
    }

    if (LocalAppLayoutInfo.current.useWideLayout) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeSectionRail(
                sections = sections,
                selectedIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex),
                onSettings = { controller.openSettings() },
                onSearch = controller::openSearch,
                onRecognition = onOpenRecognition,
                onClick = { index, section ->
                    if (section != controller.homeSection || index != pagerState.currentPage) {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    }
                },
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                pageSpacing = 16.dp,
            ) { page ->
                when (sections[page].first) {
                    HomeSection.Recommend -> ProviderContentHomeSection(
                        controller = controller,
                        section = HomeSection.Recommend,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HomeSection.Music -> ProviderContentHomeSection(
                        controller = controller,
                        section = HomeSection.Music,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HomeSection.Mine -> MineHomeSection(
                        controller = controller,
                        hasAudioPermission = hasAudioPermission,
                        onRequestAudioPermission = onRequestAudioPermission,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding),
            pageSpacing = 16.dp,
        ) { page ->
            when (sections[page].first) {
                HomeSection.Recommend -> ProviderContentHomeSection(
                    controller = controller,
                    section = HomeSection.Recommend,
                    modifier = Modifier.fillMaxSize(),
                )
                HomeSection.Music -> ProviderContentHomeSection(
                    controller = controller,
                    section = HomeSection.Music,
                    modifier = Modifier.fillMaxSize(),
                )
                HomeSection.Mine -> MineHomeSection(
                    controller = controller,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = onRequestAudioPermission,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(FuoBottomNavigationBarHeight),
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            sections.forEachIndexed { index, (section, label) ->
                NavigationBarItem(
                    selected = index == pagerState.currentPage,
                    onClick = {
                        if (section != controller.homeSection || index != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                    icon = { Icon(homeSectionIcon(section), contentDescription = label) },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                )
            }
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
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        header = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        },
    ) {
        sections.forEachIndexed { index, (section, label) ->
            NavigationRailItem(
                selected = index == selectedIndex,
                onClick = { onClick(index, section) },
                icon = { Icon(homeSectionIcon(section), contentDescription = label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRecognition) {
            Icon(Icons.Filled.Mic, contentDescription = "听歌识曲")
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
    }
}

fun homeSectionIcon(section: HomeSection): ImageVector {
    return when (section) {
        HomeSection.Recommend -> Icons.Filled.PlayArrow
        HomeSection.Music -> Icons.Filled.Album
        HomeSection.Mine -> Icons.Filled.Person
    }
}

@Composable
fun EmptyHomeSection(modifier: Modifier, title: String) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
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
