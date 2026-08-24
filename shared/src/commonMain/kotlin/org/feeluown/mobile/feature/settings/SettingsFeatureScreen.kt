package org.feeluown.mobile

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
private enum class FeatureSettingsCategory(
    val title: String,
    val supportingText: String,
) {
    Sources("音源与账号", "音源启用、排序、登录与显示范围"),
    Playback("播放与音质", "网络音质、播放策略与智能替换"),
    Appearance("外观与显示", "主题、歌词字号与歌词同步"),
    LocalMusic("本地音乐", "媒体目录扫描与短音频过滤"),
    Storage("下载与存储", "下载行为、缓存上限与清理"),
    About("关于", "版本、项目链接与诊断信息"),
}

@Serializable
private sealed interface FeatureSettingsRoute : NavKey {
    @Serializable data object Main : FeatureSettingsRoute
    @Serializable data class Category(val category: FeatureSettingsCategory) : FeatureSettingsRoute
    @Serializable data object Theme : FeatureSettingsRoute
    @Serializable data object CredentialBackup : FeatureSettingsRoute
    @Serializable data class Provider(val providerId: String) : FeatureSettingsRoute
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
fun SettingsFeatureScreen(
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    providerAuth: ProviderAuthFeatureController,
    appVersionInfo: String?,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
) {
    val settingsState by settingsController.uiState.collectAsStateWithLifecycle()
    val catalogState by providerCatalog.uiState.collectAsStateWithLifecycle()
    val authState by providerAuth.uiState.collectAsStateWithLifecycle()
    val credentialBackupActions = LocalProviderCredentialBackupActions.current
    val layoutInfo = LocalAppLayoutInfo.current
    val predictiveBackPreference = rememberPredictiveBackPreference()
    var backStack by remember { mutableStateOf<List<FeatureSettingsRoute>>(listOf(FeatureSettingsRoute.Main)) }
    var wideSelection by remember { mutableStateOf(FeatureSettingsCategory.Sources) }

    fun push(route: FeatureSettingsRoute) {
        if (backStack.lastOrNull() != route) backStack = backStack + route
    }

    fun pop() {
        if (backStack.size > 1) backStack = backStack.dropLast(1) else settingsController.close()
    }

    fun openCategory(category: FeatureSettingsCategory) {
        if (layoutInfo.useWideLayout && backStack.lastOrNull() == FeatureSettingsRoute.Main) {
            wideSelection = category
        } else {
            push(FeatureSettingsRoute.Category(category))
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
                    FeatureSettingsRoute.Main -> SettingsMainPage(
                        settings = settingsState,
                        catalog = catalogState,
                        appVersionInfo = appVersionInfo,
                        useWideLayout = layoutInfo.useWideLayout,
                        wideSelection = wideSelection,
                        onSelectCategory = { category ->
                            wideSelection = category
                            openCategory(category)
                        },
                        onOpenTheme = { push(FeatureSettingsRoute.Theme) },
                        onOpenProvider = { push(FeatureSettingsRoute.Provider(it.providerId)) },
                        onOpenCredentialBackup = { push(FeatureSettingsRoute.CredentialBackup) },
                        onBack = settingsController::close,
                        settingsController = settingsController,
                        providerCatalog = providerCatalog,
                    )
                    is FeatureSettingsRoute.Category -> SettingsCategoryPage(
                        category = route.category,
                        settings = settingsState,
                        catalog = catalogState,
                        appVersionInfo = appVersionInfo,
                        settingsController = settingsController,
                        providerCatalog = providerCatalog,
                        onOpenTheme = { push(FeatureSettingsRoute.Theme) },
                        onOpenProvider = { push(FeatureSettingsRoute.Provider(it.providerId)) },
                        onOpenCredentialBackup = { push(FeatureSettingsRoute.CredentialBackup) },
                        onBack = ::pop,
                    )
                    FeatureSettingsRoute.Theme -> SettingsScaffold(
                        title = "主题设置",
                        onBack = ::pop,
                        isLoading = settingsState.isBusy || catalogState.isLoading,
                    ) { bodyModifier ->
                        SettingsDetailColumn(modifier = bodyModifier) {
                            ThemeSettingsContent(
                                state = settingsState,
                                controller = settingsController,
                                predictiveBackPreference = predictiveBackPreference,
                            )
                        }
                    }
                    FeatureSettingsRoute.CredentialBackup -> SettingsScaffold(
                        title = "登录凭证",
                        onBack = ::pop,
                        isLoading = catalogState.isLoading,
                    ) { bodyModifier ->
                        SettingsDetailColumn(modifier = bodyModifier) {
                            ProviderCredentialBackupSettings(
                                state = catalogState,
                                actions = credentialBackupActions,
                            )
                        }
                    }
                    is FeatureSettingsRoute.Provider -> {
                        val provider = catalogState.availableProviders.firstOrNull { it.providerId == route.providerId }
                            ?: catalogState.providers.firstOrNull { it.providerId == route.providerId }
                        SettingsScaffold(
                            title = provider?.providerName ?: "音源账号",
                            onBack = ::pop,
                            isLoading = settingsState.isBusy || catalogState.isLoading,
                        ) { bodyModifier ->
                            if (provider == null) {
                                Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("该音源当前不可用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                SettingsDetailColumn(modifier = bodyModifier) {
                                    ProviderAccountSettings(
                                        provider = provider,
                                        authController = providerAuth,
                                        authUiState = authState,
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
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    useWideLayout: Boolean,
    wideSelection: FeatureSettingsCategory,
    onSelectCategory: (FeatureSettingsCategory) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    onBack: () -> Unit,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
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
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (settings.isBusy || catalog.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (useWideLayout) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    SettingsCategoryPane(
                        modifier = Modifier.width(320.dp),
                        selected = wideSelection,
                        settings = settings,
                        catalog = catalog,
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
                            settings = settings,
                            catalog = catalog,
                            appVersionInfo = appVersionInfo,
                            showHeading = true,
                            settingsController = settingsController,
                            providerCatalog = providerCatalog,
                            onOpenTheme = onOpenTheme,
                            onOpenProvider = onOpenProvider,
                            onOpenCredentialBackup = onOpenCredentialBackup,
                        )
                    }
                }
            } else {
                SettingsOverview(
                    modifier = Modifier.fillMaxSize(),
                    settings = settings,
                    catalog = catalog,
                    appVersionInfo = appVersionInfo,
                    onSelect = onSelectCategory,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCategoryPage(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = category.title,
        onBack = onBack,
        isLoading = settings.isBusy || catalog.isLoading,
    ) { bodyModifier ->
        SettingsCategoryDetail(
            category = category,
            settings = settings,
            catalog = catalog,
            appVersionInfo = appVersionInfo,
            showHeading = false,
            settingsController = settingsController,
            providerCatalog = providerCatalog,
            onOpenTheme = onOpenTheme,
            onOpenProvider = onOpenProvider,
            onOpenCredentialBackup = onOpenCredentialBackup,
            modifier = bodyModifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { SettingsBackButton(onBack) },
                colors = settingsTopAppBarColors(),
            )
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            content(Modifier.weight(1f).fillMaxWidth())
        }
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
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    onSelect: (FeatureSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDetailColumn(modifier = modifier) {
        SettingsGroup {
            FeatureSettingsCategory.entries.forEachIndexed { index, category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, settings, catalog, appVersionInfo),
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
                if (index < FeatureSettingsCategory.entries.lastIndex) SettingsDivider()
            }
        }
    }
}

@Composable
private fun SettingsCategoryPane(
    selected: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    onSelect: (FeatureSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = FuoSpacing.sm),
        ) {
            FeatureSettingsCategory.entries.forEach { category ->
                SettingsRow(
                    title = category.title,
                    supportingText = categorySummary(category, settings, catalog, appVersionInfo),
                    selected = selected == category,
                    leadingContent = { Icon(categoryIcon(category), contentDescription = null) },
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryDetail(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    showHeading: Boolean,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    onOpenTheme: () -> Unit,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    SettingsDetailColumn(modifier = modifier) {
        if (showHeading) {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs)) {
                Text(category.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    category.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (category) {
            FeatureSettingsCategory.Sources -> ProviderCatalogSettings(
                state = catalog,
                controller = providerCatalog,
                onOpenProvider = onOpenProvider,
                onOpenCredentialBackup = onOpenCredentialBackup,
            )
            FeatureSettingsCategory.Playback -> PlaybackFeatureSettings(
                state = settings,
                catalog = catalog,
                settingsController = settingsController,
                providerCatalog = providerCatalog,
            )
            FeatureSettingsCategory.Appearance -> AppearanceFeatureSettings(
                state = settings,
                controller = settingsController,
                onOpenTheme = onOpenTheme,
            )
            FeatureSettingsCategory.LocalMusic -> LocalMusicFeatureSettings(settings, settingsController)
            FeatureSettingsCategory.Storage -> StorageFeatureSettings(settings, settingsController)
            FeatureSettingsCategory.About -> AboutFeatureSettings(settings, settingsController, appVersionInfo)
        }
    }
}

@Composable
private fun SettingsDetailColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
            content = content,
        )
    }
}

@Composable
private fun ProviderCatalogSettings(
    state: ProviderCatalogUiState,
    controller: ProviderCatalogFeatureController,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
) {
    val credentialBackupActions = LocalProviderCredentialBackupActions.current
    val ordered = remember(state.availableProviders, state.providerOrderIds) {
        val order = state.providerOrderIds.withIndex().associate { it.value to it.index }
        state.availableProviders.sortedBy { order[it.providerId] ?: Int.MAX_VALUE }
    }
    var configuringProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var draggingProviderId by remember { mutableStateOf<String?>(null) }
    var dragDistance by remember { mutableStateOf(0f) }

    SettingsGroup(title = "音源") {
        ordered.forEachIndexed { index, provider ->
            val enabled = provider.providerId in state.enabledProviderIds
            val auth = state.sessions.authStates[provider.providerId]
            SettingsRow(
                title = provider.providerName,
                supportingText = providerStatusText(enabled, auth) + " · " + providerDisplaySummary(state, provider.providerId),
                selected = draggingProviderId == provider.providerId,
                enabled = !state.isLoading,
                leadingContent = {
                    Icon(
                        modifier = Modifier.pointerInput(provider.providerId, state.isLoading) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (!state.isLoading) {
                                        draggingProviderId = provider.providerId
                                        dragDistance = 0f
                                    }
                                },
                                onDragEnd = {
                                    draggingProviderId = null
                                    dragDistance = 0f
                                },
                                onDragCancel = {
                                    draggingProviderId = null
                                    dragDistance = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (state.isLoading || draggingProviderId != provider.providerId) return@detectDragGesturesAfterLongPress
                                    dragDistance += amount.y
                                    val threshold = 44.dp.toPx()
                                    when {
                                        dragDistance >= threshold -> {
                                            controller.moveProvider(provider.providerId, 1)
                                            dragDistance = 0f
                                        }
                                        dragDistance <= -threshold -> {
                                            controller.moveProvider(provider.providerId, -1)
                                            dragDistance = 0f
                                        }
                                    }
                                },
                            )
                        },
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "长按拖动排序${provider.providerName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = !state.isLoading,
                            onClick = { configuringProvider = provider },
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "配置${provider.providerName}")
                        }
                        IconButton(
                            enabled = !state.isLoading && enabled,
                            onClick = { onOpenProvider(provider) },
                        ) {
                            Icon(Icons.Filled.ManageAccounts, contentDescription = "管理${provider.providerName}账号")
                        }
                        Switch(
                            checked = enabled,
                            enabled = !state.isLoading && (!enabled || state.enabledProviderIds.size > 1),
                            onCheckedChange = { controller.setProviderEnabled(provider.providerId, it) },
                        )
                    }
                },
            )
            if (index < ordered.lastIndex) SettingsDivider()
        }
    }
    if (credentialBackupActions.isAvailable) {
        SettingsGroup(title = "登录凭证") {
            SettingsRow(
                title = "备份与恢复",
                leadingContent = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenCredentialBackup,
            )
        }
    }
    SettingsGroup(title = "说明") {
        SettingsRow(
            title = "长按拖动调整音源优先级",
            supportingText = "配置按钮单独管理搜索、推荐、探索和“我的”显示范围；账号按钮用于登录与授权。",
        )
    }
    configuringProvider?.let { provider ->
        ProviderDisplaySettingsDialog(
            state = state,
            controller = controller,
            provider = provider,
            onDismissRequest = { configuringProvider = null },
        )
    }
}

@Composable
private fun ProviderCredentialBackupSettings(
    state: ProviderCatalogUiState,
    actions: ProviderCredentialBackupActions,
) {
    val orderedLoggedInProviders = remember(state.availableProviders, state.providerOrderIds, state.sessions.authStates) {
        val order = state.providerOrderIds.withIndex().associate { it.value to it.index }
        state.availableProviders
            .filter { provider -> state.sessions.authStates[provider.providerId]?.isLoggedIn == true }
            .sortedBy { provider -> order[provider.providerId] ?: Int.MAX_VALUE }
    }
    val exportAll = actions.exportAll
    val exportProvider = actions.exportProvider
    val importBackup = actions.importBackup

    SettingsGroup(title = "备份") {
        SettingsRow(
            title = "导出全部",
            supportingText = orderedLoggedInProviders.takeIf { it.isNotEmpty() }?.let { "${it.size} 个音源" },
            enabled = exportAll != null && orderedLoggedInProviders.isNotEmpty(),
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = exportAll,
        )
    }

    SettingsGroup(title = "单独导出") {
        if (orderedLoggedInProviders.isEmpty()) {
            SettingsRow(title = "暂无已登录音源")
        } else {
            orderedLoggedInProviders.forEachIndexed { index, provider ->
                val auth = state.sessions.authStates[provider.providerId]
                SettingsRow(
                    title = provider.providerName,
                    supportingText = auth?.userName?.takeIf { it.isNotBlank() },
                    enabled = exportProvider != null,
                    leadingContent = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = exportProvider?.let { action -> { action(provider) } },
                )
                if (index < orderedLoggedInProviders.lastIndex) SettingsDivider()
            }
        }
    }

    SettingsGroup(title = "恢复") {
        SettingsRow(
            title = "从文件恢复",
            enabled = importBackup != null,
            leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = importBackup,
        )
    }
}

@Composable
private fun ProviderDisplaySettingsDialog(
    state: ProviderCatalogUiState,
    controller: ProviderCatalogFeatureController,
    provider: ProviderInfo,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(provider.providerName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
                Text("显示范围", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    ProviderDisplaySection.Search to "搜索",
                    ProviderDisplaySection.Recommend to "推荐",
                    ProviderDisplaySection.Explore to "探索",
                    ProviderDisplaySection.Mine to "我的",
                ).forEach { (section, label) ->
                    val selected = providerShownIn(state, provider.providerId, section)
                    SettingsRow(
                        title = label,
                        supportingText = if (selected) "已显示" else "已隐藏",
                        enabled = !state.isLoading,
                        trailingContent = {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {
                            controller.setDisplayProviderEnabled(section, provider.providerId, !selected)
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismissRequest) { Text("完成") } },
    )
}

private fun providerShownIn(state: ProviderCatalogUiState, providerId: String, section: ProviderDisplaySection): Boolean =
    providerId in when (section) {
        ProviderDisplaySection.Search -> state.searchProviderIds
        ProviderDisplaySection.Recommend -> state.recommendProviderIds
        ProviderDisplaySection.Explore -> state.exploreProviderIds
        ProviderDisplaySection.Mine -> state.mineProviderIds
        ProviderDisplaySection.Replace -> state.replacementProviderIds
    }

private fun providerDisplaySummary(state: ProviderCatalogUiState, providerId: String): String =
    listOf(
        ProviderDisplaySection.Search to "搜索",
        ProviderDisplaySection.Recommend to "推荐",
        ProviderDisplaySection.Explore to "探索",
        ProviderDisplaySection.Mine to "我的",
    ).filter { (section, _) -> providerShownIn(state, providerId, section) }
        .joinToString("、") { it.second }
        .ifBlank { "未显示" }

@Composable
private fun PlaybackFeatureSettings(
    state: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
) {
    val settings = state.settings
    val busy = state.isBusy || catalog.isLoading
    SettingsGroup(title = "音质") {
        SettingsChoiceRow(
            title = "Wi‑Fi",
            supportingText = "连接 Wi‑Fi 时优先使用的音质",
            value = settings.wifiAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = settings.wifiAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !busy,
            onSelect = settingsController::setWifiAudioQualityPolicy,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "蜂窝网络",
            supportingText = "使用移动数据时优先使用的音质",
            value = settings.cellularAudioQualityPolicy.label,
            options = AudioQualityPolicy.entries,
            selected = settings.cellularAudioQualityPolicy,
            optionLabel = AudioQualityPolicy::label,
            enabled = !busy,
            onSelect = settingsController::setCellularAudioQualityPolicy,
        )
    }

    SettingsGroup(title = "播放策略") {
        SettingsToggleRow(
            title = "其他应用播放时自动暂停",
            supportingText = "检测到其他应用开始播放时暂停当前播放",
            checked = settings.pauseOnOtherAppPlayback,
            enabled = !busy,
        ) { enabled -> settingsController.update { it.copy(pauseOnOtherAppPlayback = enabled) } }
        SettingsDivider(startPadding = FuoSpacing.lg)
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text("资源不可用时", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnavailablePlaybackPolicy.entries.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = settings.unavailablePlaybackPolicy == policy,
                        enabled = !busy,
                        onClick = { settingsController.update { it.copy(unavailablePlaybackPolicy = policy) } },
                        shape = SegmentedButtonDefaults.itemShape(index, UnavailablePlaybackPolicy.entries.size),
                        colors = settingsSegmentedButtonColors(),
                    ) { Text(policy.label) }
                }
            }
        }
    }

    SmartReplacementFeatureSettings(
        settings = settings,
        catalog = catalog,
        busy = busy,
        settingsController = settingsController,
        providerCatalog = providerCatalog,
    )
}

@Composable
private fun SmartReplacementFeatureSettings(
    settings: AppSettings,
    catalog: ProviderCatalogUiState,
    busy: Boolean,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
) {
    val enabled = settings.unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace
    val providers = catalog.providers
    val presets = listOf(
        "宽松" to 0.45,
        "平衡" to DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        "严格" to 0.70,
    )
    fun isPreset(score: Double): Boolean = presets.any { (_, value) -> abs(score - value) < 0.001 }
    var customExpanded by remember { mutableStateOf(!isPreset(settings.smartReplacementMinScore)) }
    LaunchedEffect(settings.smartReplacementMinScore) {
        if (!isPreset(settings.smartReplacementMinScore)) customExpanded = true
    }

    SettingsGroup(title = "智能替换") {
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
        ) {
            Text(
                if (enabled) {
                    "原音源无法播放时，从所选音源中搜索并匹配可播放版本"
                } else {
                    "当前资源不可用策略不是“智能替换”，切换后以下设置生效"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("替换音源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (providers.isEmpty()) {
                Text("没有已启用的音源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                ) {
                    providers.forEach { provider ->
                        val selected = provider.providerId in settings.smartReplacementProviderIds
                        FilterChip(
                            selected = selected,
                            enabled = enabled && !busy,
                            onClick = {
                                providerCatalog.setDisplayProviderEnabled(
                                    ProviderDisplaySection.Replace,
                                    provider.providerId,
                                    !selected,
                                )
                            },
                            label = { Text(provider.providerName) },
                            colors = settingsFilterChipColors(),
                        )
                    }
                }
                Text(
                    if (providers.size < 2) {
                        "仅启用一个音源时无法跨源替换；播放时会自动排除歌曲自身来源"
                    } else {
                        "播放时会自动排除歌曲自身来源；候选音源顺序沿用上方音源排序"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("匹配严格度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                presets.forEach { (label, score) ->
                    FilterChip(
                        selected = !customExpanded && abs(settings.smartReplacementMinScore - score) < 0.001,
                        enabled = enabled && !busy,
                        onClick = {
                            customExpanded = false
                            settingsController.update { it.copy(smartReplacementMinScore = score) }
                        },
                        label = { Text(label) },
                        colors = settingsFilterChipColors(),
                    )
                }
                FilterChip(
                    selected = customExpanded,
                    enabled = enabled && !busy,
                    onClick = { customExpanded = true },
                    label = { Text("自定义") },
                    colors = settingsFilterChipColors(),
                )
            }
            if (customExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("最低匹配分")
                    Text(formatReplacementScore(settings.smartReplacementMinScore), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = settings.smartReplacementMinScore.toFloat(),
                    onValueChange = { value ->
                        settingsController.update {
                            it.copy(smartReplacementMinScore = roundReplacementScore(value.toDouble()))
                        }
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分数越高匹配越严格", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppearanceFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    onOpenTheme: () -> Unit,
) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "主题") {
        SettingsChoiceRow(
            title = "主题模式",
            supportingText = "选择浅色、深色或跟随系统",
            value = settings.themeMode.label,
            leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
            options = ThemeMode.entries,
            selected = settings.themeMode,
            optionLabel = ThemeMode::label,
            enabled = enabled,
        ) { value -> controller.update { it.copy(themeMode = value) } }
        SettingsDivider()
        SettingsRow(
            title = "主题设置",
            supportingText = "${settings.themeColorScheme.label} · 调色板与色彩规范",
            enabled = enabled,
            leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = onOpenTheme,
        )
    }

    SettingsGroup(title = "播放显示") {
        Column(
            modifier = Modifier.padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text("歌词字号", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LyricFontSize.entries.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = settings.lyricFontSize == size,
                        enabled = enabled,
                        onClick = { controller.update { it.copy(lyricFontSize = size) } },
                        shape = SegmentedButtonDefaults.itemShape(index, LyricFontSize.entries.size),
                        colors = settingsSegmentedButtonColors(),
                    ) { Text(size.label) }
                }
            }
        }
        if (state.statusBarLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "状态栏歌词",
                supportingText = "通过词幕在系统状态栏显示当前歌词",
                checked = settings.statusBarLyricsEnabled,
                enabled = enabled,
                onCheckedChange = controller::setStatusBarLyricsEnabled,
            )
        }
        if (state.bydInstrumentLyricsAvailable) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            SettingsToggleRow(
                title = "比亚迪仪表歌词",
                supportingText = "将当前歌词同步到驾驶仪表的三行歌词区域",
                checked = settings.bydInstrumentLyricsEnabled,
                enabled = enabled,
                onCheckedChange = controller::setBydInstrumentLyricsEnabled,
            )
        }
    }
}

@Composable
private fun ThemeSettingsContent(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    predictiveBackPreference: PredictiveBackPreference,
) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "颜色") {
        SettingsChoiceRow(
            title = "强调色",
            supportingText = "选择应用主要颜色方案",
            value = settings.themeColorScheme.label,
            options = ThemeColorScheme.entries,
            selected = settings.themeColorScheme,
            optionLabel = ThemeColorScheme::label,
            enabled = enabled,
        ) { value -> controller.update { it.copy(themeColorScheme = value) } }
        SettingsDivider()
        SettingsChoiceRow(
            title = "调色板风格",
            supportingText = "Material 3 动态配色算法风格",
            value = settings.themePaletteStyle.label,
            options = ThemePaletteStyle.entries,
            selected = settings.themePaletteStyle,
            optionLabel = ThemePaletteStyle::label,
            enabled = enabled,
            onSelect = controller::setThemePaletteStyle,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "色彩规范",
            supportingText = "选择 Material 3 色彩规范版本",
            value = settings.themeColorSpec.label,
            options = ThemeColorSpec.entries,
            selected = settings.themeColorSpec,
            optionLabel = ThemeColorSpec::label,
            enabled = enabled,
            onSelect = controller::setThemeColorSpec,
        )
    }
    if (predictiveBackPreference.isSupported) {
        SettingsGroup(title = "导航") {
            SettingsToggleRow(
                title = "预测性返回手势",
                supportingText = "返回手势过程中预览上一页",
                checked = predictiveBackPreference.enabled,
                enabled = enabled,
                onCheckedChange = predictiveBackPreference.onEnabledChange,
            )
        }
    }
    SettingsGroup(title = "封面") {
        SettingsToggleRow(
            title = "封面动态取色",
            supportingText = "播放时根据当前封面调整界面颜色",
            checked = settings.dynamicCoverColorEnabled,
            enabled = enabled,
        ) { value -> controller.update { it.copy(dynamicCoverColorEnabled = value) } }
    }
}

@Composable
private fun LocalMusicFeatureSettings(state: SettingsFeatureUiState, controller: SettingsFeatureController) {
    LaunchedEffect(Unit) { controller.refreshLocalMusicDirectories() }
    val enabled = !state.isBusy
    SettingsGroup(title = "扫描设置") {
        SettingsChoiceRow(
            title = "忽略短音频",
            supportingText = "扫描时过滤低于指定时长的音频",
            value = if (state.localMusic.minDurationSeconds == 0) "不过滤" else "${state.localMusic.minDurationSeconds} 秒",
            options = listOf(0, 15, 30, 60, 120),
            selected = state.localMusic.minDurationSeconds,
            optionLabel = { if (it == 0) "不过滤" else "$it 秒" },
            enabled = enabled,
            onSelect = controller::setLocalMusicMinDurationSeconds,
        )
    }
    SettingsGroup(title = "媒体目录") {
        if (state.localMusic.directories.isEmpty()) {
            SettingsRow(title = "暂无可用目录", supportingText = "刷新本地音乐后将在这里显示媒体目录")
        } else {
            state.localMusic.directories.forEachIndexed { index, directory ->
                val directoryEnabled = !isLocalMusicDirectoryExcluded(directory.id, state.localMusic.excludedDirectoryIds)
                SettingsToggleRow(
                    title = directory.name,
                    supportingText = "${directory.trackCount} 首",
                    checked = directoryEnabled,
                    enabled = enabled,
                ) { controller.setLocalMusicDirectoryEnabled(directory.id, it) }
                if (index < state.localMusic.directories.lastIndex) SettingsDivider(startPadding = FuoSpacing.lg)
            }
        }
    }
}

@Composable
private fun StorageFeatureSettings(state: SettingsFeatureUiState, controller: SettingsFeatureController) {
    val settings = state.settings
    val enabled = !state.isBusy
    SettingsGroup(title = "下载") {
        SettingsRow(
            title = "下载管理",
            supportingText = "${state.downloadTasks.count { it.status == DownloadTaskStatus.Downloading }} 个下载中",
            enabled = enabled,
            leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = controller::openDownloadManager,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "并行下载数量",
            supportingText = "同时进行的下载任务数量",
            value = settings.downloadParallelism.toString(),
            options = (1..5).toList(),
            selected = settings.downloadParallelism,
            optionLabel = { it.toString() },
            enabled = enabled,
            onSelect = controller::setDownloadParallelism,
        )
    }
    SettingsGroup(title = "缓存") {
        SettingsChoiceRow(
            title = "音频缓存上限",
            supportingText = "本地音频缓存最大占用空间",
            value = "${settings.audioCacheLimitMb} MB",
            options = listOf(128, 256, 512, 1024, 2048),
            selected = settings.audioCacheLimitMb,
            optionLabel = { "$it MB" },
            enabled = enabled,
            onSelect = controller::setAudioCacheLimitMb,
        )
        SettingsDivider()
        SettingsChoiceRow(
            title = "图片缓存上限",
            supportingText = "封面等图片缓存最大占用空间",
            value = "${settings.imageCacheLimitMb} MB",
            options = listOf(64, 128, 256, 512),
            selected = settings.imageCacheLimitMb,
            optionLabel = { "$it MB" },
            enabled = enabled,
            onSelect = controller::setImageCacheLimitMb,
        )
        SettingsDivider()
        SettingsRow(
            title = "当前缓存",
            supportingText = formatCacheBytes(state.cacheUsage.totalBytes),
            trailingContent = {
                OutlinedButton(onClick = controller::clearCache, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(FuoSpacing.xs))
                    Text("清理")
                }
            },
        )
    }
}

@Composable
private fun AboutFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    appVersionInfo: String?,
) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup(title = "应用信息") {
        appVersionInfo?.takeIf { it.isNotBlank() }?.let { version ->
            SettingsRow(
                title = "版本",
                trailingContent = {
                    Text(
                        version.removePrefix("版本 "),
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
    if (state.debugLogViewerAvailable) {
        SettingsGroup(title = "诊断") {
            SettingsRow(
                title = "应用日志",
                supportingText = "查看调试日志与错误信息",
                enabled = !state.isBusy,
                leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = controller::openDebugLogs,
            )
        }
    }
}

@Composable
private fun ProviderAccountSettings(
    provider: ProviderInfo,
    authController: ProviderAuthFeatureController,
    authUiState: ProviderAuthUiState,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
    onImportYtmusicOAuthFile: (() -> Unit)?,
    onStartYtmusicOAuth: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val credentialBackupActions = LocalProviderCredentialBackupActions.current
    val auth = authController.authStateFor(provider)
    val busy = authController.isBusy(provider.providerId)
    val modes = provider.supportedLoginModes.toList().ifEmpty { listOf(ProviderLoginMode.Cookie) }
    var mode by remember(provider.providerId, modes) { mutableStateOf(modes.first()) }
    val header = authController.headerInput(provider.providerId)
    val oauth = authController.oauthInput(provider.providerId)
    val oauthFlow = authUiState.ytmusicOAuthFlow.takeIf { provider.providerId == "ytmusic" }

    SettingsGroup(title = "账号状态") {
        SettingsRow(
            title = if (auth.isLoggedIn) "已登录" else "未登录",
            supportingText = auth.userName.orEmpty().ifBlank { provider.providerName },
            leadingContent = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
        )
        if (auth.isLoggedIn) {
            SettingsDivider(startPadding = FuoSpacing.lg)
            Box(Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
                OutlinedButton(
                    onClick = { onLogoutProvider(provider) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("退出登录") }
            }
        }
    }

    if (auth.isLoggedIn && credentialBackupActions.exportProvider != null) {
        SettingsGroup(title = "登录凭证") {
            SettingsRow(
                title = "导出登录凭证",
                enabled = !busy,
                leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { credentialBackupActions.exportProvider.invoke(provider) },
            )
        }
    }

    if (!auth.isLoggedIn) {
        SettingsGroup(title = "登录方式") {
            if (modes.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
                    modes.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = mode == candidate,
                            enabled = !busy,
                            onClick = { mode = candidate },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            colors = settingsSegmentedButtonColors(),
                        ) { Text(providerLoginModeLabel(candidate)) }
                    }
                }
            }
            when (mode) {
                ProviderLoginMode.WebView -> Box(Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
                    Button(
                        onClick = { onOpenProviderWebLogin(provider) },
                        enabled = provider.loginConfig != null && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("网页登录") }
                }
                ProviderLoginMode.Cookie -> Column(
                    modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
                ) {
                    OutlinedTextField(
                        value = authController.cookieInput(provider.providerId),
                        onValueChange = { authController.onCookiesChange(provider.providerId, it) },
                        label = { Text("Cookie / Cookie JSON") },
                        minLines = 3,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { authController.loginWithCookies(provider.providerId, authController.cookieInput(provider.providerId)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("使用 Cookie 登录") }
                }
                ProviderLoginMode.Headers -> Column(
                    modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
                ) {
                    OutlinedTextField(
                        value = header.authorization,
                        onValueChange = { authController.onHeaderAuthorizationChange(provider.providerId, it) },
                        label = { Text("Authorization") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = header.cookie,
                        onValueChange = { authController.onHeaderCookieChange(provider.providerId, it) },
                        label = { Text("Cookie") },
                        minLines = 2,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { authController.loginWithHeaders(provider.providerId) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("使用 Headers 登录") }
                    if (provider.providerId == "ytmusic") {
                        onImportYtmusicHeaderFile?.let { action ->
                            OutlinedButton(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("导入 ytmusic_header.json")
                            }
                        }
                    }
                }
                ProviderLoginMode.OAuth -> Column(
                    modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
                ) {
                    Text(
                        "使用 Google Cloud「TVs and Limited Input devices」类型的 OAuth 客户端，可导入 client_secret.json / oauth.json。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = oauth.clientId,
                        onValueChange = { authController.onOAuthClientIdChange(provider.providerId, it) },
                        label = { Text("client_id") },
                        enabled = !busy && oauthFlow == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = oauth.clientSecret,
                        onValueChange = { authController.onOAuthClientSecretChange(provider.providerId, it) },
                        label = { Text("client_secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy && oauthFlow == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (oauthFlow == null) {
                        val startAction = onStartYtmusicOAuth ?: authController::startYtmusicTvOAuthLogin
                        Button(onClick = startAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("使用 Google 登录（TV）")
                        }
                        onImportYtmusicOAuthFile?.let { action ->
                            OutlinedButton(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("导入 client_secret.json / oauth.json")
                            }
                        }
                    } else {
                        val verificationUrl = oauthFlow.verificationUrlWithCode.ifBlank { oauthFlow.verificationUrl }
                        LaunchedEffect(oauthFlow.userCode, verificationUrl) {
                            if (!oauthFlow.browserOpened && verificationUrl.isNotBlank()) {
                                runCatching { uriHandler.openUri(verificationUrl) }
                                    .onSuccess { authController.markYtmusicOAuthBrowserOpened() }
                            }
                        }
                        Text(
                            if (oauthFlow.browserOpened) "浏览器已打开，请输入下方验证码" else oauthFlow.statusMessage,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier.padding(FuoSpacing.lg),
                                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                            ) {
                                Text("设备验证码", style = MaterialTheme.typography.labelMedium)
                                Text(oauthFlow.userCode, style = MaterialTheme.typography.headlineMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
                                    Button(onClick = authController::copyYtmusicOAuthUserCode) { Text("复制验证码") }
                                    TextButton(
                                        enabled = verificationUrl.isNotBlank(),
                                        onClick = {
                                            runCatching { uriHandler.openUri(verificationUrl) }
                                                .onSuccess { authController.markYtmusicOAuthBrowserOpened() }
                                        },
                                    ) { Text("重新打开浏览器") }
                                }
                            }
                        }
                        Text(verificationUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = authController::cancelYtmusicTvOAuthLogin) { Text("取消授权") }
                    }
                }
            }
        }
    }

    authController.authError(provider.providerId)?.let { error ->
        SettingsGroup(title = "错误") { SettingsRow(title = error, titleColor = MaterialTheme.colorScheme.error) }
    }
    authUiState.feedback?.let { feedback ->
        SettingsGroup(title = "状态") { SettingsRow(title = feedback) }
    }
}

@Composable
private fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = FuoSpacing.sm),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) { Column(content = content) }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    supportingText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    val contentAlpha = if (enabled) 1f else 0.55f
    ListItem(
        headlineContent = {
            Text(title, color = titleColor.copy(alpha = contentAlpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        modifier = Modifier.fillMaxWidth().then(clickableModifier),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    supportingText: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        supportingText = supportingText,
        enabled = enabled,
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    supportingText: String? = null,
    value: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    leadingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = title,
            supportingText = supportingText,
            leadingContent = leadingContent,
            enabled = enabled,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            },
            onClick = { if (enabled) expanded = true },
        )
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    trailingIcon = if (option == selected) {
                        { Text("✓", color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsDivider(startPadding: Dp = 0.dp) {
    HorizontalDivider(modifier = Modifier.padding(start = startPadding), color = MaterialTheme.colorScheme.outlineVariant)
}

private fun categorySummary(
    category: FeatureSettingsCategory,
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
): String = when (category) {
    FeatureSettingsCategory.Sources -> {
        val loggedIn = catalog.sessions.authStates.values.count { it.isLoggedIn }
        if (loggedIn > 0) "${catalog.enabledProviderIds.size} 个音源已启用 · $loggedIn 个已登录"
        else "${catalog.enabledProviderIds.size} 个音源已启用"
    }
    FeatureSettingsCategory.Playback ->
        "Wi‑Fi ${settings.settings.wifiAudioQualityPolicy.label} · ${settings.settings.unavailablePlaybackPolicy.label}"
    FeatureSettingsCategory.Appearance -> "${settings.settings.themeMode.label} · ${settings.settings.themeColorScheme.label}"
    FeatureSettingsCategory.LocalMusic -> "${settings.localMusic.directories.size} 个媒体目录"
    FeatureSettingsCategory.Storage -> "并行下载 ${settings.settings.downloadParallelism} · 缓存与清理"
    FeatureSettingsCategory.About -> appVersionInfo ?: "FuoEvolve"
}

private fun categoryIcon(category: FeatureSettingsCategory): ImageVector = when (category) {
    FeatureSettingsCategory.Sources -> Icons.Filled.ManageAccounts
    FeatureSettingsCategory.Playback -> Icons.Filled.Tune
    FeatureSettingsCategory.Appearance -> Icons.Filled.Palette
    FeatureSettingsCategory.LocalMusic -> Icons.Filled.Settings
    FeatureSettingsCategory.Storage -> Icons.Filled.Download
    FeatureSettingsCategory.About -> Icons.Filled.Code
}

private fun providerStatusText(enabled: Boolean, auth: ProviderAuthState?): String {
    val login = auth?.takeIf { it.isLoggedIn }?.let { state ->
        state.userName?.takeIf { it.isNotBlank() }?.let { "已登录 · $it" } ?: "已登录"
    }
    return listOfNotNull(login, if (enabled) "已启用" else "未启用").joinToString(" · ")
}

private fun providerLoginModeLabel(mode: ProviderLoginMode): String = when (mode) {
    ProviderLoginMode.WebView -> "网页"
    ProviderLoginMode.Cookie -> "Cookie"
    ProviderLoginMode.Headers -> "Headers"
    ProviderLoginMode.OAuth -> "OAuth"
}

private fun roundReplacementScore(value: Double): Double =
    ((value.coerceIn(0.0, 1.0) * 20.0 + 0.5).toInt() / 20.0).coerceIn(0.0, 1.0)

private fun formatReplacementScore(value: Double): String {
    val hundredths = (value.coerceIn(0.0, 1.0) * 100.0 + 0.5).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
