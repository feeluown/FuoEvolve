package org.feeluown.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AppInitializationLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在加载设置",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    controller: FuoPlayerController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val availableProviders = controller.orderedAvailableProviders()
    var selectedProviderIds by rememberSaveable {
        mutableStateOf(controller.enabledProviderIds.toList())
    }
    var bilibiliReplacementOnly by rememberSaveable {
        mutableStateOf(
            "bilibili" in controller.enabledProviderIds &&
                controller.smartReplacementProviderIds == setOf("bilibili") &&
                ProviderDisplaySection.entries
                    .filter { it != ProviderDisplaySection.Replace }
                    .none { controller.isProviderShownIn("bilibili", it) },
        )
    }
    val selectedProviders = availableProviders.filter { it.providerId in selectedProviderIds }
    val pageCount = selectedProviders.size + 2
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val isSourcePage = pagerState.currentPage == 0
    val isQualityPage = pagerState.currentPage == pageCount - 1

    LaunchedEffect(availableProviders) {
        if (availableProviders.isEmpty()) return@LaunchedEffect
        val availableIds = availableProviders.map { it.providerId }.toSet()
        val normalizedSelection = selectedProviderIds.filter { it in availableIds }
        selectedProviderIds = normalizedSelection.ifEmpty {
            controller.enabledProviderIds.intersect(availableIds)
                .ifEmpty { setOf(availableProviders.first().providerId) }
                .toList()
        }
    }
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("初始设置") },
                navigationIcon = {
                    if (!isSourcePage) {
                        IconButton(
                            enabled = !controller.isLoading,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一步")
                        }
                    }
                },
            )
        },
        bottomBar = {
            OnboardingFooter(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                isLoading = controller.isLoading,
                actionLabel = when {
                    isSourcePage -> "继续"
                    isQualityPage -> "完成"
                    controller.authStateFor(selectedProviders[pagerState.currentPage - 1]).isLoggedIn -> "继续"
                    else -> "跳过"
                },
                onAction = {
                    scope.launch {
                        when {
                            isSourcePage -> {
                                val applied = controller.configureOnboardingProviders(
                                    selectedProviderIds = selectedProviderIds.toSet(),
                                    bilibiliReplacementOnly = bilibiliReplacementOnly,
                                )
                                if (applied) pagerState.animateScrollToPage(1)
                            }
                            isQualityPage -> controller.completeOnboarding()
                            else -> pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            userScrollEnabled = !controller.isLoading && !isSourcePage,
            verticalAlignment = Alignment.Top,
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    page == 0 -> ProviderSelectionPage(
                        providers = availableProviders,
                        selectedProviderIds = selectedProviderIds.toSet(),
                        bilibiliReplacementOnly = bilibiliReplacementOnly,
                        enabled = !controller.isLoading,
                        message = controller.message.takeIf { message ->
                            (selectedProviderIds.isEmpty() && message.startsWith("请至少")) ||
                                (bilibiliReplacementOnly && selectedProviderIds == listOf("bilibili") &&
                                    message.startsWith("Bilibili"))
                        }.orEmpty(),
                        onProviderSelected = { providerId, selected ->
                            selectedProviderIds = if (selected) {
                                (selectedProviderIds + providerId).distinct()
                            } else {
                                selectedProviderIds - providerId
                            }
                            if (providerId == "bilibili" && !selected) {
                                bilibiliReplacementOnly = false
                            }
                        },
                        onBilibiliReplacementOnlyChange = { bilibiliReplacementOnly = it },
                    )
                    page == pageCount - 1 -> AudioQualityOnboardingPage(controller)
                    else -> ProviderLoginOnboardingPage(
                        controller = controller,
                        provider = selectedProviders[page - 1],
                        onOpenProviderWebLogin = onOpenProviderWebLogin,
                        onLogoutProvider = onLogoutProvider,
                        onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSelectionPage(
    providers: List<ProviderInfo>,
    selectedProviderIds: Set<String>,
    bilibiliReplacementOnly: Boolean,
    enabled: Boolean,
    message: String,
    onProviderSelected: (String, Boolean) -> Unit,
    onBilibiliReplacementOnlyChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "选择要启用的音源",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "至少选择一个音源，之后可以逐一登录；这些设置也可以稍后在设置中修改。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (providers.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("音源正在初始化")
                }
            }
        } else {
            providers.forEach { provider ->
                val selected = provider.providerId in selectedProviderIds
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) {
                            onProviderSelected(provider.providerId, !selected)
                        },
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            enabled = enabled,
                            onCheckedChange = { onProviderSelected(provider.providerId, it) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.providerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (selected) "将启用此音源" else "不会加载此音源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        if ("bilibili" in selectedProviderIds) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = enabled) {
                            onBilibiliReplacementOnlyChange(!bilibiliReplacementOnly)
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = bilibiliReplacementOnly,
                        enabled = enabled,
                        onCheckedChange = onBilibiliReplacementOnlyChange,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bilibili 仅作为替换音源", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "不在搜索和首页展示，只在原音源资源不可用时参与智能替换。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (message.startsWith("请至少") || message.startsWith("Bilibili")) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProviderLoginOnboardingPage(
    controller: FuoPlayerController,
    provider: ProviderInfo,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "登录 ${provider.providerName}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "登录后可以使用个人歌单和推荐内容；也可以跳过，稍后再登录。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderLoginPanel(
            controller = controller,
            provider = provider,
            onOpenProviderWebLogin = onOpenProviderWebLogin,
            onLogoutProvider = onLogoutProvider,
            onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
        )
    }
}

@Composable
private fun AudioQualityOnboardingPage(controller: FuoPlayerController) {
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "设置默认音质",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "分别为 Wi‑Fi 和蜂窝网络选择音质。音质越高，使用的流量和缓存空间越多。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AudioQualitySettingsPanel(controller)
    }
}

@Composable
private fun OnboardingFooter(
    currentPage: Int,
    pageCount: Int,
    isLoading: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(pageCount) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
            Button(
                modifier = Modifier.widthIn(min = 220.dp),
                enabled = !isLoading,
                onClick = onAction,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = if (isLoading) "请稍候" else actionLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
