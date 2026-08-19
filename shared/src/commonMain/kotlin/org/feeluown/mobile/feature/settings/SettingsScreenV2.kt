package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
private enum class SettingsCategory(
    val title: String,
    val supportingText: String,
) {
    Sources("音源与账号", "音源启用、排序、登录与显示范围"),
    Playback("播放与音质", "网络音质、播放策略与智能替换"),
    Appearance("外观与显示", "主题、歌词字号与状态栏歌词"),
    LocalMusic("本地音乐", "媒体目录扫描与短音频过滤"),
    Storage("下载与存储", "下载行为、缓存上限与清理"),
    About("关于", "版本、项目链接与诊断信息"),
}

@Serializable
private sealed interface SettingsRoute : NavKey {
    @Serializable
    data object Main : SettingsRoute

    @Serializable
    data class Category(val category: SettingsCategory) : SettingsRoute

    @Serializable
    data object Theme : SettingsRoute

    @Serializable
    data class Provider(val providerId: String) : SettingsRoute
}

private fun settingsPageTransition(
    initialOffsetX: (Int) -> Int,
    targetOffsetX: (Int) -> Int,
): ContentTransform = (
    slideInHorizontally(
        initialOffsetX = initialOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeIn(animationSpec = tween(FuoMotion.pageFadeMillis))
    ) togetherWith (
    slideOutHorizontally(
        targetOffsetX = targetOffsetX,
        animationSpec = tween(FuoMotion.pageTransitionMillis),
    ) + fadeOut(animationSpec = tween(FuoMotion.pageFadeMillis))
    )

private fun settingsForwardPageTransition(): ContentTransform =
    settingsPageTransition(initialOffsetX = { it }, targetOffsetX = { -it })

private fun settingsPopPageTransition(): ContentTransform =
    settingsPageTransition(initialOffsetX = { -it }, targetOffsetX = { it })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenV2(
    controller: FuoPlayerController,
    themePaletteStyle: ThemePaletteStyle,
    onThemePaletteStyleChange: (ThemePaletteStyle) -> Unit,
    themeColorSpec: ThemeColorSpec,
    onThemeColorSpecChange: (ThemeColorSpec) -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    appVersionInfo: String?,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val predictiveBackPreference = rememberPredictiveBackPreference()
    var backStack by remember { mutableStateOf<List<SettingsRoute>>(listOf(SettingsRoute.Main)) }
    var wideSelection by remember { mutableStateOf(SettingsCategory.Sources) }

    fun push(route: SettingsRoute) {
        if (backStack.lastOrNull() != route) {
            backStack = backStack + route
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        } else {
            controller.closeSettings()
        }
    }

    fun openCategory(category: SettingsCategory) {
        if (layoutInfo.useWideLayout && backStack.lastOrNull() == SettingsRoute.Main) {
            wideSelection = category
        } else {
            push(SettingsRoute.Category(category))
        }
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = ::pop,
        transitionSpec = { settingsForwardPageTransition() },
        popTransitionSpec = { settingsPopPageTransition() },
        predictivePopTransitionSpec = { settingsPopPageTransition() },
        entryProvider = { route ->
            NavEntry(key = route) {
                when (route) {
                    SettingsRoute.Main -> {
                        SettingsMainPage(
                            controller = controller,
                            appVersionInfo = appVersionInfo,
                            useWideLayout = layoutInfo.useWideLayout,
                            wideSelection = wideSelection,
                            onSelectCategory = { category ->
                                wideSelection = category
                                openCategory(category)
                            },
                            onOpenTheme = { push(SettingsRoute.Theme) },
                            onOpenProvider = { provider -> push(SettingsRoute.Provider(provider.providerId)) },
                            onBack = controller::closeSettings,
                        )
                    }

                    is SettingsRoute.Category -> {
                        SettingsCategoryPage(
                            category = route.category,
                            controller = controller,
                            appVersionInfo = appVersionInfo,
                            onOpenTheme = { push(SettingsRoute.Theme) },
                            onOpenProvider = { provider -> push(SettingsRoute.Provider(provider.providerId)) },
                            onBack = ::pop,
                        )
                    }

                    SettingsRoute.Theme -> {
                        SettingsScaffold(
                            title = "主题设置",
                            onBack = ::pop,
                        ) { bodyModifier ->
                            ThemeSettingsContent(
                                controller = controller,
                                themePaletteStyle = themePaletteStyle,
                                onThemePaletteStyleChange = onThemePaletteStyleChange,
                                themeColorSpec = themeColorSpec,
                                onThemeColorSpecChange = onThemeColorSpecChange,
                                predictiveBackPreference = predictiveBackPreference,
                                modifier = bodyModifier,
                            )
                        }
                    }

                    is SettingsRoute.Provider -> {
                        val provider = controller.orderedProviders()
                            .firstOrNull { it.providerId == route.providerId }
                        SettingsScaffold(
                            title = provider?.providerName ?: "音源账号",
                            onBack = ::pop,
                        ) { bodyModifier ->
                            if (provider == null) {
                                Box(
                                    modifier = bodyModifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "该音源当前不可用",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                SettingsDetailColumn(modifier = bodyModifier) {
                                    ProviderLoginPanel(
                                        controller = controller,
                                        provider = provider,
                                        onOpenProviderWebLogin = onOpenProviderWebLogin,
                                        onLogoutProvider = onLogoutProvider,
                                        onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                                        onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                                        onStartYtmusicOAuth = onStartYtmusicOAuth,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
    PlatformLegacyBackHandler(
        enabled = predictiveBackPreference.isSupported && !predictiveBackPreference.enabled,
        onBack = ::pop,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMainPage(
    controller: FuoPlayerController,
    appVersionInfo: String?,
    useWideLayout: Boolean,
    wideSelection: SettingsCategory,
    onSelectCategory: (SettingsCategory) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (useWideLayout) {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = { SettingsBackButton(onBack) },
                    colors = settingsTopAppBarColors(),
                )
            } else {
                LargeTopAppBar(
                    title = { Text("设置") },
                    navigationIcon = { SettingsBackButton(onBack) },
                    colors = settingsTopAppBarColors(),
                )
            }
        },
    ) { paddingValues ->
        if (useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
            ) {
                SettingsCategoryPane(
                    modifier = Modifier.width(320.dp),
                    selected = wideSelection,
                    controller = controller,
                    appVersionInfo = appVersionInfo,
                    onSelect = onSelectCategory,
                )
                Surface(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    SettingsCategoryDetail(
                        category = wideSelection,
                        controller = controller,
                        appVersionInfo = appVersionInfo,
                        showHeading = true,
                        onOpenTheme = onOpenTheme,
                        onOpenProvider = onOpenProvider,
                    )
                }
            }
        } else {
            SettingsOverview(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                controller = controller,
                appVersionInfo = appVersionInfo,
                onSelect = onSelectCategory,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCategoryPage(
    category: SettingsCategory,
    controller: FuoPlayerController,
    appVersionInfo: String?,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = category.title,
        onBack = onBack,
    ) { bodyModifier ->
        SettingsCategoryDetail(
            category = category,
            controller = controller,
            appVersionInfo = appVersionInfo,
            showHeading = false,
            onOpenTheme = onOpenTheme,
            onOpenProvider = onOpenProvider,
            modifier = bodyModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { SettingsBackButton(onBack) },
                colors = settingsTopAppBarColors(),
            )
        },
    ) { paddingValues ->
        content(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
        )
    }
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun SettingsOverview(
    controller: FuoPlayerController,
    appVersionInfo: String?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDetailColumn(modifier = modifier) {
        SettingsGroup {
            SettingsCategory.entries.forEachIndexed { index, category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, controller, appVersionInfo),
                    leadingContent = {
                        Icon(
                            imageVector = categoryIcon(category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { onSelect(category) },
                )
                if (index < SettingsCategory.entries.lastIndex) {
                    SettingsDivider()
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryPane(
    selected: SettingsCategory,
    controller: FuoPlayerController,
    appVersionInfo: String?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = FuoSpacing.sm),
        ) {
            SettingsCategory.entries.forEach { category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, controller, appVersionInfo),
                    selected = selected == category,
                    leadingContent = {
                        Icon(categoryIcon(category), contentDescription = null)
                    },
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryDetail(
    category: SettingsCategory,
    controller: FuoPlayerController,
    appVersionInfo: String?,
    showHeading: Boolean,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    SettingsDetailColumn(modifier = modifier) {
        if (showHeading) {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs)) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = category.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (category) {
            SettingsCategory.Sources -> ProviderSwitchPanel(
                controller = controller,
                onOpenProviderLogin = onOpenProvider,
            )

            SettingsCategory.Playback -> {
                AudioQualitySettingsPanelV2(controller)
                PlaybackPolicySettingsPanelV2(controller)
                SmartReplacementSettingsPanel(controller)
            }

            SettingsCategory.Appearance -> {
                SettingsGroup(title = "主题") {
                    SettingsChoiceRow(
                        title = "主题模式",
                        supportingText = "选择浅色、深色或跟随系统",
                        value = controller.themeMode.label,
                        leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                        options = ThemeMode.entries,
                        selected = controller.themeMode,
                        optionLabel = ThemeMode::label,
                        enabled = !controller.isLoading,
                        onSelect = controller::onThemeModeChange,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "主题设置",
                        supportingText = "${controller.themeColorScheme.label} · 调色板与色彩规范",
                        leadingContent = {
                            Icon(
                                Icons.Filled.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = onOpenTheme,
                    )
                }
                PlayerDisplaySettingsPanelV2(controller)
            }

            SettingsCategory.LocalMusic -> LocalMusicScanSettingsPanel(controller)

            SettingsCategory.Storage -> {
                DownloadSettingsPanelV2(controller)
                CacheSettingsPanel(controller)
            }

            SettingsCategory.About -> AboutSettingsPanelV2(
                controller = controller,
                appVersionInfo = appVersionInfo,
            )
        }
    }
}

@Composable
private fun SettingsDetailColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
            content = content,
        )
    }
}

@Composable
private fun PlayerDisplaySettingsPanelV2(controller: FuoPlayerController) {
    SettingsGroup(title = "播放显示") {
        Column(
            modifier = Modifier.padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text(
                text = "歌词字号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LyricFontSize.entries.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = controller.lyricFontSize == size,
                        onClick = { controller.onLyricFontSizeChange(size) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LyricFontSize.entries.size,
                        ),
                    ) {
                        Text(size.label)
                    }
                }
            }
        }
        if (controller.isStatusBarLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "状态栏歌词",
                supportingText = "通过词幕在系统状态栏显示当前歌词",
                checked = controller.statusBarLyricsEnabled,
                enabled = !controller.isLoading,
                onCheckedChange = controller::onStatusBarLyricsEnabledChange,
            )
        }
    }
}

@Composable
private fun AudioQualitySettingsPanelV2(controller: FuoPlayerController) {
    SettingsGroup(title = "音质") {
        SettingsChoiceRow(
            title = "Wi‑Fi",
            supportingText = "连接 Wi‑Fi 时优先使用的音质",
            value = controller.wifiAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = controller.wifiAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !controller.isLoading,
            onSelect = controller::onWifiAudioQualityPolicyChange,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "蜂窝网络",
            supportingText = "使用移动数据时优先使用的音质",
            value = controller.cellularAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = controller.cellularAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !controller.isLoading,
            onSelect = controller::onCellularAudioQualityPolicyChange,
        )
    }
}

@Composable
private fun PlaybackPolicySettingsPanelV2(controller: FuoPlayerController) {
    SettingsGroup(title = "播放策略") {
        SettingsToggleRow(
            title = "其他应用播放时自动暂停",
            supportingText = "检测到其他应用开始播放时暂停当前播放",
            checked = controller.pauseOnOtherAppPlayback,
            enabled = !controller.isLoading,
            onCheckedChange = controller::onPauseOnOtherAppPlaybackChange,
        )
        SettingsDivider(startPadding = FuoSpacing.lg)
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text(
                text = "资源不可用时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnavailablePlaybackPolicy.entries.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = controller.unavailablePlaybackPolicy == policy,
                        onClick = { controller.onUnavailablePlaybackPolicyChange(policy) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = UnavailablePlaybackPolicy.entries.size,
                        ),
                    ) {
                        Text(policy.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSettingsPanelV2(controller: FuoPlayerController) {
    SettingsGroup(title = "下载") {
        SettingsRow(
            title = "下载管理",
            supportingText = "${controller.downloadTasks.count { it.status == DownloadTaskStatus.Downloading }} 个下载中",
            leadingContent = {
                Icon(Icons.Filled.Download, contentDescription = null)
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = controller::openDownloadManager,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "并行下载数量",
            supportingText = "同时进行的下载任务数量",
            value = controller.downloadParallelism.toString(),
            options = (1..5).toList(),
            selected = controller.downloadParallelism,
            optionLabel = { it.toString() },
            enabled = !controller.isLoading,
            onSelect = controller::onDownloadParallelismChange,
        )
    }
}

@Composable
private fun AboutSettingsPanelV2(
    controller: FuoPlayerController,
    appVersionInfo: String?,
) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup(title = "应用信息") {
        appVersionInfo?.takeIf { it.isNotBlank() }?.let { versionInfo ->
            SettingsRow(
                title = "版本",
                trailingContent = {
                    Text(
                        text = versionInfo.removePrefix("版本 "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            SettingsDivider()
        }
        SettingsRow(
            title = "FuoEvolve 源代码",
            supportingText = "GitHub 项目主页",
            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = { uriHandler.openUri(FUO_EVOLVE_SOURCE_URL) },
        )
        SettingsDivider()
        SettingsRow(
            title = "FeelUOwn 主项目",
            supportingText = "上游项目主页",
            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = { uriHandler.openUri(FEELUOWN_SOURCE_URL) },
        )
    }
    if (controller.isDebugLogViewerAvailable) {
        SettingsGroup(title = "诊断") {
            SettingsRow(
                title = "应用日志",
                supportingText = "查看、筛选和导出调试日志",
                leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = controller::openDebugLogs,
            )
        }
    }
}

@Composable
private fun ThemeSettingsContent(
    controller: FuoPlayerController,
    themePaletteStyle: ThemePaletteStyle,
    onThemePaletteStyleChange: (ThemePaletteStyle) -> Unit,
    themeColorSpec: ThemeColorSpec,
    onThemeColorSpecChange: (ThemeColorSpec) -> Unit,
    predictiveBackPreference: PredictiveBackPreference,
    modifier: Modifier = Modifier,
) {
    val darkTheme = resolvedDarkTheme(controller.themeMode, isSystemInDarkTheme())
    SettingsDetailColumn(modifier = modifier) {
        SettingsGroup(title = "主题配色") {
            SettingsChoiceRow(
                title = "强调色",
                supportingText = "选择 Material 3 主题使用的配色种子",
                value = controller.themeColorScheme.label,
                leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                options = ThemeColorScheme.entries,
                selected = controller.themeColorScheme,
                optionLabel = ThemeColorScheme::label,
                enabled = !controller.isLoading,
                optionLeading = { scheme ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                themePreviewColor(
                                    themeColorScheme = scheme,
                                    darkTheme = darkTheme,
                                    paletteStyle = themePaletteStyle,
                                    colorSpec = themeColorSpec,
                                ),
                            ),
                    )
                },
                onSelect = controller::onThemeColorSchemeChange,
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "调色板风格",
                supportingText = "用于推导 Material 3 调色板的色调算法",
                value = themePaletteStyle.label,
                leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                options = ThemePaletteStyle.entries,
                selected = themePaletteStyle,
                optionLabel = ThemePaletteStyle::label,
                enabled = !controller.isLoading,
                onSelect = onThemePaletteStyleChange,
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "色彩规范",
                supportingText = "Material 3 色彩规范版本",
                value = themeColorSpec.label,
                leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                options = ThemeColorSpec.entries,
                selected = themeColorSpec,
                optionLabel = ThemeColorSpec::label,
                enabled = !controller.isLoading,
                onSelect = onThemeColorSpecChange,
            )
        }
        if (predictiveBackPreference.isSupported) {
            SettingsGroup(title = "导航") {
                SettingsToggleRow(
                    title = "预测性返回手势",
                    supportingText = "返回手势过程中预览上一页",
                    checked = predictiveBackPreference.enabled,
                    enabled = !controller.isLoading,
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    },
                    onCheckedChange = predictiveBackPreference.onEnabledChange,
                )
            }
        }
        SettingsGroup(title = "播放器") {
            SettingsToggleRow(
                title = "封面动态取色",
                supportingText = "根据当前播放封面生成播放器主题色",
                checked = controller.dynamicCoverColorEnabled,
                enabled = !controller.isLoading,
                leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                onCheckedChange = controller::onDynamicCoverColorEnabledChange,
            )
        }
        Text(
            modifier = Modifier.padding(horizontal = FuoSpacing.md),
            text = "动态取色只影响播放页；封面不可用时自动回退到上方选择的强调色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
        title?.let {
            Text(
                modifier = Modifier.padding(horizontal = FuoSpacing.md),
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interactiveModifier = if (onClick == null) {
        modifier
    } else {
        modifier
            .fuoInteractive()
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
    }
    ListItem(
        modifier = interactiveModifier,
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text ->
            {
                Text(
                    text = text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .fuoInteractive()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = leadingContent,
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    supportingText: String,
    value: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
    optionLeading: (@Composable (T) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var displayedValue by remember(value) { mutableStateOf(value) }
    var displayedSelected by remember(selected) { mutableStateOf(selected) }

    SettingsRow(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        supportingText = supportingText,
        enabled = enabled,
        onClick = { expanded = true },
        leadingContent = leadingContent,
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
                ) {
                    Text(
                        text = displayedValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { option ->
                        val isSelected = option == displayedSelected
                        DropdownMenuItem(
                            modifier = if (isSelected) {
                                Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            } else {
                                Modifier
                            },
                            text = {
                                Text(
                                    text = optionLabel(option),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            leadingIcon = {
                                when {
                                    isSelected -> Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    optionLeading != null -> optionLeading(option)
                                    else -> Spacer(Modifier.size(24.dp))
                                }
                            },
                            onClick = {
                                displayedSelected = option
                                displayedValue = optionLabel(option)
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingsDivider(startPadding: androidx.compose.ui.unit.Dp = 64.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startPadding, end = FuoSpacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun categoryIcon(category: SettingsCategory): ImageVector = when (category) {
    SettingsCategory.Sources -> Icons.Filled.ManageAccounts
    SettingsCategory.Playback -> Icons.Filled.Settings
    SettingsCategory.Appearance -> Icons.Filled.Palette
    SettingsCategory.LocalMusic -> Icons.Filled.AutoAwesome
    SettingsCategory.Storage -> Icons.Filled.Download
    SettingsCategory.About -> Icons.Filled.Code
}

private fun categorySummary(
    category: SettingsCategory,
    controller: FuoPlayerController,
    appVersionInfo: String?,
): String = when (category) {
    SettingsCategory.Sources -> "${controller.enabledProviderIds.size} 个音源已启用"
    SettingsCategory.Playback ->
        "Wi‑Fi ${controller.wifiAudioQualityPolicy.label} · ${controller.unavailablePlaybackPolicy.label}"
    SettingsCategory.Appearance -> "${controller.themeMode.label} · ${controller.themeColorScheme.label}"
    SettingsCategory.LocalMusic -> if (controller.localMusicDirectories.isEmpty()) {
        "媒体库扫描与过滤"
    } else {
        "${controller.localMusicDirectories.size} 个媒体目录"
    }
    SettingsCategory.Storage -> "并行下载 ${controller.downloadParallelism} · 缓存与清理"
    SettingsCategory.About -> appVersionInfo?.takeIf { it.isNotBlank() } ?: "FuoEvolve"
}
