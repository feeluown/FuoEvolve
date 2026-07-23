package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun LoadingIndicator(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                .padding(paddingValues)
                .padding(horizontal = if (layoutInfo.useWideLayout) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (layoutInfo.useWideLayout) 6.dp else 12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
            HomeSectionPager(
                controller = controller,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                onOpenRecognition = onOpenRecognition,
                modifier = Modifier.weight(1f),
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
            modifier = modifier.fillMaxWidth(),
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTabs(
            sections = sections,
            selectedIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex),
            indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
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
                .fillMaxWidth(),
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
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            Spacer(Modifier.weight(1f))
            sections.forEachIndexed { index, (section, label) ->
                val selected = index == selectedIndex
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick(index, section) }
                        .padding(vertical = 4.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(homeSectionIcon(section), contentDescription = label)
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
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRecognition) {
                Icon(Icons.Filled.Mic, contentDescription = "听歌识曲")
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
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
@Suppress("DEPRECATION")
fun HomeSectionTabs(
    sections: List<Pair<HomeSection, String>>,
    selectedIndex: Int,
    indicatorOffsetFraction: Float,
    onClick: (Int, HomeSection) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            val current = tabPositions.getOrNull(selectedIndex) ?: return@TabRow
            val targetIndex = when {
                indicatorOffsetFraction > 0f -> (selectedIndex + 1).coerceAtMost(tabPositions.lastIndex)
                indicatorOffsetFraction < 0f -> (selectedIndex - 1).coerceAtLeast(0)
                else -> selectedIndex
            }
            val target = tabPositions[targetIndex]
            val progress = indicatorOffsetFraction.absoluteValue.coerceIn(0f, 1f)
            val indicatorLeft = lerp(current.left, target.left, progress)
            val indicatorWidth = lerp(current.width, target.width, progress)
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomStart)
                    .offset { IntOffset(indicatorLeft.roundToPx(), 0) }
                    .width(indicatorWidth),
            )
        },
    ) {
        sections.forEachIndexed { index, (section, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onClick(index, section) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = homeSectionIcon(section),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
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
