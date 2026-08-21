package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class FeatureSettingsCategory(val title: String, val subtitle: String) {
    Sources("音源与账号", "启用、顺序、展示范围和登录"),
    Playback("播放与音质", "音质、不可用歌曲和智能替换"),
    Appearance("外观与显示", "主题、歌词和状态栏歌词"),
    LocalMusic("本地音乐", "扫描目录和短音频过滤"),
    Storage("下载与存储", "并行下载、缓存和清理"),
    About("关于", "版本、项目和诊断"),
}

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
    var category by remember { mutableStateOf<FeatureSettingsCategory?>(null) }
    var providerId by remember { mutableStateOf<String?>(null) }

    val provider = providerId?.let { id ->
        catalogState.availableProviders.firstOrNull { it.providerId == id }
            ?: catalogState.providers.firstOrNull { it.providerId == id }
    }
    val title = provider?.providerName ?: category?.title ?: "设置"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                providerId != null -> providerId = null
                                category != null -> category = null
                                else -> settingsController.close()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (settingsState.isBusy || catalogState.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                provider != null -> ProviderAccountSettings(
                    provider = provider,
                    authController = providerAuth,
                    authUiState = authState,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onLogoutProvider = onLogoutProvider,
                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                    onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                    onStartYtmusicOAuth = onStartYtmusicOAuth,
                )
                category == null -> SettingsCategoryList(
                    settings = settingsState,
                    catalog = catalogState,
                    appVersionInfo = appVersionInfo,
                    onOpen = { category = it },
                )
                else -> SettingsCategoryContent(
                    category = category!!,
                    settingsState = settingsState,
                    catalogState = catalogState,
                    settingsController = settingsController,
                    providerCatalog = providerCatalog,
                    onOpenProvider = { providerId = it.providerId },
                    appVersionInfo = appVersionInfo,
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryList(
    settings: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    appVersionInfo: String?,
    onOpen: (FeatureSettingsCategory) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(FeatureSettingsCategory.entries) { category ->
            ListItem(
                headlineContent = { Text(category.title) },
                supportingContent = {
                    Text(
                        when (category) {
                            FeatureSettingsCategory.Sources -> "${catalog.enabledProviderIds.size} 个音源已启用"
                            FeatureSettingsCategory.Playback ->
                                "Wi‑Fi ${settings.settings.wifiAudioQualityPolicy.label} · ${settings.settings.unavailablePlaybackPolicy.label}"
                            FeatureSettingsCategory.Appearance ->
                                "${settings.settings.themeMode.label} · ${settings.settings.themeColorScheme.label}"
                            FeatureSettingsCategory.LocalMusic -> "${settings.localMusic.directories.size} 个媒体目录"
                            FeatureSettingsCategory.Storage -> "并行下载 ${settings.settings.downloadParallelism} · 缓存与清理"
                            FeatureSettingsCategory.About -> appVersionInfo ?: "FuoEvolve"
                        }
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpen(category) }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("打开")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsCategoryContent(
    category: FeatureSettingsCategory,
    settingsState: SettingsFeatureUiState,
    catalogState: ProviderCatalogUiState,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    onOpenProvider: (ProviderInfo) -> Unit,
    appVersionInfo: String?,
) {
    when (category) {
        FeatureSettingsCategory.Sources -> ProviderCatalogSettings(
            state = catalogState,
            controller = providerCatalog,
            onOpenProvider = onOpenProvider,
        )
        FeatureSettingsCategory.Playback -> PlaybackFeatureSettings(
            state = settingsState,
            catalog = catalogState,
            settingsController = settingsController,
            providerCatalog = providerCatalog,
        )
        FeatureSettingsCategory.Appearance -> AppearanceFeatureSettings(settingsState, settingsController)
        FeatureSettingsCategory.LocalMusic -> LocalMusicFeatureSettings(settingsState, settingsController)
        FeatureSettingsCategory.Storage -> StorageFeatureSettings(settingsState, settingsController)
        FeatureSettingsCategory.About -> AboutFeatureSettings(settingsState, settingsController, appVersionInfo)
    }
}

@Composable
private fun ProviderCatalogSettings(
    state: ProviderCatalogUiState,
    controller: ProviderCatalogFeatureController,
    onOpenProvider: (ProviderInfo) -> Unit,
) {
    val ordered = remember(state.availableProviders, state.providerOrderIds) {
        val order = state.providerOrderIds.withIndex().associate { it.value to it.index }
        state.availableProviders.sortedBy { order[it.providerId] ?: Int.MAX_VALUE }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "启用的音源会参与内容加载；搜索、推荐、探索、我的和替换范围可独立选择。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ordered, key = ProviderInfo::providerId) { provider ->
            val enabled = provider.providerId in state.enabledProviderIds
            ListItem(
                headlineContent = { Text(provider.providerName) },
                supportingContent = {
                    Text(
                        state.sessions.authStates[provider.providerId]
                            ?.takeIf { it.isLoggedIn }
                            ?.userName
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "已登录 · $it" }
                            ?: if (enabled) "已启用" else "未启用"
                    )
                },
                leadingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { controller.setProviderEnabled(provider.providerId, it) },
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { controller.moveProvider(provider.providerId, -1) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                        }
                        IconButton(onClick = { controller.moveProvider(provider.providerId, 1) }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                        }
                    }
                },
            )
            if (enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderScopeChip("搜索", provider.providerId in state.searchProviderIds) {
                        controller.setDisplayProviderEnabled(ProviderDisplaySection.Search, provider.providerId, it)
                    }
                    ProviderScopeChip("推荐", provider.providerId in state.recommendProviderIds) {
                        controller.setDisplayProviderEnabled(ProviderDisplaySection.Recommend, provider.providerId, it)
                    }
                    ProviderScopeChip("探索", provider.providerId in state.exploreProviderIds) {
                        controller.setDisplayProviderEnabled(ProviderDisplaySection.Explore, provider.providerId, it)
                    }
                    ProviderScopeChip("我的", provider.providerId in state.mineProviderIds) {
                        controller.setDisplayProviderEnabled(ProviderDisplaySection.Mine, provider.providerId, it)
                    }
                    ProviderScopeChip("替换", provider.providerId in state.replacementProviderIds) {
                        controller.setDisplayProviderEnabled(ProviderDisplaySection.Replace, provider.providerId, it)
                    }
                }
            }
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onOpenProvider(provider) }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("账号设置")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProviderScopeChip(label: String, selected: Boolean, onSelected: (Boolean) -> Unit) {
    FilterChip(selected = selected, onClick = { onSelected(!selected) }, label = { Text(label) })
}

@Composable
private fun PlaybackFeatureSettings(
    state: SettingsFeatureUiState,
    catalog: ProviderCatalogUiState,
    settingsController: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
) {
    val settings = state.settings
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            FeatureChoiceRow("Wi‑Fi 音质", settings.wifiAudioQualityPolicy, AudioQualityPolicy.entries) {
                settingsController.setWifiAudioQualityPolicy(it)
            }
            FeatureChoiceRow("蜂窝网络音质", settings.cellularAudioQualityPolicy, AudioQualityPolicy.entries) {
                settingsController.setCellularAudioQualityPolicy(it)
            }
            FeatureChoiceRow("歌曲不可用时", settings.unavailablePlaybackPolicy, UnavailablePlaybackPolicy.entries) {
                settingsController.update { current -> current.copy(unavailablePlaybackPolicy = it) }
            }
            SettingsToggle(
                title = "其他应用播放时自动暂停",
                checked = settings.pauseOnOtherAppPlayback,
            ) { enabled -> settingsController.update { it.copy(pauseOnOtherAppPlayback = enabled) } }
            Text("智能替换音源", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            catalog.providers.forEach { provider ->
                SettingsToggle(
                    title = provider.providerName,
                    checked = provider.providerId in settings.smartReplacementProviderIds,
                ) { enabled ->
                    providerCatalog.setDisplayProviderEnabled(ProviderDisplaySection.Replace, provider.providerId, enabled)
                }
            }
            Text(
                "最低匹配分 ${formatReplacementScore(settings.smartReplacementMinScore)}",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = settings.smartReplacementMinScore.toFloat(),
                onValueChange = { value ->
                    settingsController.update { it.copy(smartReplacementMinScore = value.toDouble().coerceIn(0.0, 1.0)) }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun AppearanceFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
) {
    val settings = state.settings
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            FeatureChoiceRow("主题模式", settings.themeMode, ThemeMode.entries) { value ->
                controller.update { it.copy(themeMode = value) }
            }
            FeatureChoiceRow("强调色", settings.themeColorScheme, ThemeColorScheme.entries) { value ->
                controller.update { it.copy(themeColorScheme = value) }
            }
            FeatureChoiceRow("调色板风格", settings.themePaletteStyle, ThemePaletteStyle.entries) {
                controller.setThemePaletteStyle(it)
            }
            FeatureChoiceRow("色彩规范", settings.themeColorSpec, ThemeColorSpec.entries) {
                controller.setThemeColorSpec(it)
            }
            FeatureChoiceRow("歌词字号", settings.lyricFontSize, LyricFontSize.entries) { value ->
                controller.update { it.copy(lyricFontSize = value) }
            }
            SettingsToggle("封面动态取色", settings.dynamicCoverColorEnabled) { enabled ->
                controller.update { it.copy(dynamicCoverColorEnabled = enabled) }
            }
            if (state.statusBarLyricsAvailable) {
                SettingsToggle(
                    title = "状态栏歌词",
                    checked = settings.statusBarLyricsEnabled,
                    onChange = controller::setStatusBarLyricsEnabled,
                )
            }
        }
    }
}

@Composable
private fun LocalMusicFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
) {
    LaunchedEffect(Unit) { controller.refreshLocalMusicDirectories() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            FeatureChoiceRow(
                "忽略短音频",
                state.localMusic.minDurationSeconds,
                listOf(0, 15, 30, 60, 120),
                label = { if (it == 0) "不过滤" else "$it 秒" },
                onSelect = controller::setLocalMusicMinDurationSeconds,
            )
            Text("扫描目录", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(state.localMusic.directories, key = LocalMusicDirectory::id) { directory ->
            val enabled = !isLocalMusicDirectoryExcluded(directory.id, state.localMusic.excludedDirectoryIds)
            SettingsToggle(
                title = directory.name,
                checked = enabled,
                supporting = "${directory.trackCount} 首",
            ) { controller.setLocalMusicDirectoryEnabled(directory.id, it) }
        }
    }
}

@Composable
private fun StorageFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
) {
    val settings = state.settings
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("下载管理") },
                supportingContent = {
                    Text("${state.downloadTasks.count { it.status == DownloadTaskStatus.Downloading }} 个下载中")
                },
                leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = controller::openDownloadManager) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "打开")
                    }
                },
            )
            FeatureChoiceRow("并行下载数量", settings.downloadParallelism, (1..5).toList(), onSelect = controller::setDownloadParallelism)
            FeatureChoiceRow("音频缓存上限", settings.audioCacheLimitMb, listOf(128, 256, 512, 1024, 2048), label = { "$it MB" }) {
                controller.setAudioCacheLimitMb(it)
            }
            FeatureChoiceRow("图片缓存上限", settings.imageCacheLimitMb, listOf(64, 128, 256, 512), label = { "$it MB" }) {
                controller.setImageCacheLimitMb(it)
            }
            ListItem(
                headlineContent = { Text("当前缓存") },
                supportingContent = { Text(formatCacheBytes(state.cacheUsage.totalBytes)) },
                trailingContent = {
                    OutlinedButton(onClick = controller::clearCache) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("清理")
                    }
                },
            )
        }
    }
}

@Composable
private fun AboutFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
    appVersionInfo: String?,
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(headlineContent = { Text("FuoEvolve") }, supportingContent = { Text(appVersionInfo ?: "") })
            ListItem(
                headlineContent = { Text("项目主页") },
                supportingContent = { Text(FEELUOWN_SOURCE_URL) },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { uriHandler.openUri(FEELUOWN_SOURCE_URL) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("打开项目主页")
            }
            if (state.debugLogViewerAvailable) {
                ListItem(
                    headlineContent = { Text("应用日志") },
                    leadingContent = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = controller::openDebugLogs) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "打开")
                        }
                    },
                )
            }
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
    val auth = authController.authStateFor(provider)
    val busy = authController.isBusy(provider.providerId)
    val modes = provider.supportedLoginModes.toList().ifEmpty { listOf(ProviderLoginMode.Cookie) }
    var mode by remember(provider.providerId, modes) { mutableStateOf(modes.first()) }
    val header = authController.headerInput(provider.providerId)
    val oauth = authController.oauthInput(provider.providerId)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(if (auth.isLoggedIn) "已登录" else "未登录") },
                supportingContent = { Text(auth.userName.orEmpty().ifBlank { provider.providerName }) },
            )
            if (auth.isLoggedIn) {
                OutlinedButton(
                    onClick = { onLogoutProvider(provider) },
                    enabled = !busy,
                    modifier = Modifier.padding(16.dp),
                ) { Text("退出登录") }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modes.forEach { candidate ->
                        FilterChip(
                            selected = mode == candidate,
                            onClick = { mode = candidate },
                            label = { Text(providerLoginModeLabel(candidate)) },
                        )
                    }
                }
                when (mode) {
                    ProviderLoginMode.WebView -> Button(
                        onClick = { onOpenProviderWebLogin(provider) },
                        enabled = provider.loginConfig != null && !busy,
                        modifier = Modifier.padding(16.dp),
                    ) { Text("网页登录") }
                    ProviderLoginMode.Cookie -> {
                        OutlinedTextField(
                            value = authController.cookieInput(provider.providerId),
                            onValueChange = { authController.onCookiesChange(provider.providerId, it) },
                            label = { Text("Cookie / Cookie JSON") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                        Button(
                            onClick = {
                                authController.loginWithCookies(provider.providerId, authController.cookieInput(provider.providerId))
                            },
                            enabled = !busy,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text("使用 Cookie 登录") }
                    }
                    ProviderLoginMode.Headers -> {
                        OutlinedTextField(
                            value = header.authorization,
                            onValueChange = { authController.onHeaderAuthorizationChange(provider.providerId, it) },
                            label = { Text("Authorization") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        OutlinedTextField(
                            value = header.cookie,
                            onValueChange = { authController.onHeaderCookieChange(provider.providerId, it) },
                            label = { Text("Cookie") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Button(
                            onClick = { authController.loginWithHeaders(provider.providerId) },
                            enabled = !busy,
                            modifier = Modifier.padding(16.dp),
                        ) { Text("使用 Headers 登录") }
                        if (provider.providerId == "ytmusic") {
                            onImportYtmusicHeaderFile?.let { action ->
                                TextButton(onClick = action, modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Text("导入 ytmusic_header.json")
                                }
                            }
                        }
                    }
                    ProviderLoginMode.OAuth -> {
                        OutlinedTextField(
                            value = oauth.clientId,
                            onValueChange = { authController.onOAuthClientIdChange(provider.providerId, it) },
                            label = { Text("client_id") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        OutlinedTextField(
                            value = oauth.clientSecret,
                            onValueChange = { authController.onOAuthClientSecretChange(provider.providerId, it) },
                            label = { Text("client_secret") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        onStartYtmusicOAuth?.let { action ->
                            Button(onClick = action, enabled = !busy, modifier = Modifier.padding(16.dp)) {
                                Text("Google TV OAuth 登录")
                            }
                        }
                        onImportYtmusicOAuthFile?.let { action ->
                            TextButton(onClick = action, modifier = Modifier.padding(horizontal = 16.dp)) {
                                Text("导入 OAuth JSON")
                            }
                        }
                        authUiState.ytmusicOAuthFlow?.let { flow ->
                            Text(
                                "验证码 ${flow.userCode}\n${flow.verificationUrl}\n${flow.statusMessage}",
                                modifier = Modifier.padding(16.dp),
                            )
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = authController::copyYtmusicOAuthUserCode) { Text("复制验证码") }
                                OutlinedButton(onClick = authController::cancelYtmusicTvOAuthLogin) { Text("取消") }
                            }
                        }
                    }
                }
            }
            authController.authError(provider.providerId)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            authUiState.feedback?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    supporting: String? = null,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { value -> ({ Text(value) }) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun <T> FeatureChoiceRow(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String = { option ->
        when (option) {
            is AudioQualityPolicy -> option.label
            is UnavailablePlaybackPolicy -> option.label
            is ThemeMode -> option.label
            is ThemeColorScheme -> option.label
            is ThemePaletteStyle -> option.label
            is ThemeColorSpec -> option.label
            is LyricFontSize -> option.label
            else -> option.toString()
        }
    },
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(label(selected)) },
            trailingContent = {
                TextButton(onClick = { expanded = true }) { Text("更改") }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun providerLoginModeLabel(mode: ProviderLoginMode): String = when (mode) {
    ProviderLoginMode.WebView -> "网页"
    ProviderLoginMode.Cookie -> "Cookie"
    ProviderLoginMode.Headers -> "Headers"
    ProviderLoginMode.OAuth -> "OAuth"
}

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