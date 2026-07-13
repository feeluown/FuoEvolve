package org.feeluown.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val DEBUG_LOG_THREADTIME_LEVEL_REGEX =
    Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([DIWEAF])\s+""")
private val DEBUG_LOG_TIME_LEVEL_REGEX =
    Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+([DIWEAF])/""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: FuoPlayerController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    appVersionInfo: String?,
) {
    val loginProviderId = controller.settingsLoginProviderId
    val loginProvider = controller.orderedProviders().firstOrNull { it.providerId == loginProviderId }
    val layoutInfo = LocalAppLayoutInfo.current
    LaunchedEffect(loginProviderId, controller.providers) {
        if (loginProviderId != null && loginProvider == null) {
            controller.closeSettingsProviderLogin()
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(loginProvider?.providerName ?: "设置") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (loginProvider != null) {
                                controller.closeSettingsProviderLogin()
                            } else {
                                controller.closeSettings()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                },
            )
        },
    ) { paddingValues ->
        if (loginProvider != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProviderLoginPanel(
                    controller = controller,
                    provider = loginProvider,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onLogoutProvider = onLogoutProvider,
                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                )
            }
        } else if (layoutInfo.useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ProviderSwitchPanel(
                        controller = controller,
                        onOpenProviderLogin = { provider -> controller.openSettingsProviderLogin(provider.providerId) },
                    )
                    AudioQualitySettingsPanel(controller)
                    PlaybackPolicySettingsPanel(controller)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PlayerDisplaySettingsPanel(controller)
                    LocalMusicScanSettingsPanel(controller)
                    CacheSettingsPanel(controller)
                    if (controller.isDebugLogViewerAvailable) {
                        DebugSettingsPanel(controller)
                    }
                    AppInfoPanel(appVersionInfo)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProviderSwitchPanel(
                    controller = controller,
                    onOpenProviderLogin = { provider -> controller.openSettingsProviderLogin(provider.providerId) },
                )
                AudioQualitySettingsPanel(controller)
                PlaybackPolicySettingsPanel(controller)
                PlayerDisplaySettingsPanel(controller)
                LocalMusicScanSettingsPanel(controller)
                CacheSettingsPanel(controller)
                if (controller.isDebugLogViewerAvailable) {
                    DebugSettingsPanel(controller)
                }
                AppInfoPanel(appVersionInfo)
            }
        }
    }
}
@Composable
fun AppInfoPanel(appVersionInfo: String?) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "应用信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            appVersionInfo?.takeIf { it.isNotBlank() }?.let { versionInfo ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "版本号",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        modifier = Modifier.weight(1f),
                        text = versionInfo.removePrefix("版本 "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SourceLinkButton(
                text = "源代码",
                onClick = { uriHandler.openUri(FUO_EVOLVE_SOURCE_URL) },
            )
            SourceLinkButton(
                text = "FeelUOwn 主项目",
                onClick = { uriHandler.openUri(FEELUOWN_SOURCE_URL) },
            )
        }
    }
}

@Composable
fun SourceLinkButton(
    text: String,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Icon(Icons.Filled.Code, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(8.dp))
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
    }
}

const val FUO_EVOLVE_SOURCE_URL = "https://github.com/feeluown/FuoEvolve"
const val FEELUOWN_SOURCE_URL = "https://github.com/feeluown/FeelUOwn"

@Composable
fun ProviderSwitchPanel(
    controller: FuoPlayerController,
    onOpenProviderLogin: (ProviderInfo) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "音源",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (controller.availableProviders.isEmpty()) {
                ProviderContentMessage("音源正在初始化")
            } else {
                val orderedProviders = controller.orderedAvailableProviders()
                orderedProviders.forEachIndexed { index, provider ->
                    val isEnabled = controller.isProviderEnabled(provider.providerId)
                    val authState = controller.authStateFor(provider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.providerName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = providerStatusText(isEnabled, authState),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = !controller.isLoading && index > 0,
                                onClick = { controller.moveProvider(provider.providerId, -1) },
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移${provider.providerName}")
                            }
                            IconButton(
                                enabled = !controller.isLoading && index < orderedProviders.lastIndex,
                                onClick = { controller.moveProvider(provider.providerId, 1) },
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移${provider.providerName}")
                            }
                            TextButton(
                                enabled = !controller.isLoading && isEnabled,
                                onClick = { onOpenProviderLogin(provider) },
                            ) {
                                Text(if (authState.isLoggedIn) "管理" else "登录")
                            }
                        }
                        Checkbox(
                            checked = isEnabled,
                            enabled = !controller.isLoading &&
                                (isEnabled && controller.enabledProviderIds.size > 1 || !isEnabled),
                            onCheckedChange = { controller.onProviderEnabledChange(provider.providerId, it) },
                        )
                    }
                }
            }
        }
    }
}

fun providerStatusText(isEnabled: Boolean, authState: ProviderAuthState): String {
    val enabledText = if (isEnabled) "已启用" else "未启用"
    val loginText = if (isEnabled && authState.isLoggedIn) "已登录" else "未登录"
    return "$enabledText · $loginText"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderLoginPanel(
    controller: FuoPlayerController,
    provider: ProviderInfo,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
) {
    val authState = controller.authStateFor(provider)
    val supportedLoginModes = listOf(
        ProviderLoginMode.WebView,
        ProviderLoginMode.Cookie,
        ProviderLoginMode.Headers,
    ).filter { it in provider.supportedLoginModes }
    val activeLoginMode = supportedLoginModes
        .firstOrNull { it == controller.providerLoginMode }
        ?: supportedLoginModes.firstOrNull()
        ?: ProviderLoginMode.Cookie
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = provider.providerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (authState.isLoggedIn) {
                    "已登录：${authState.userName.orEmpty()}"
                } else {
                    "未登录"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val authFeedback = controller.message.takeIf { message ->
                message.contains(provider.providerName) ||
                    message.contains("音源运行时尚未接入")
            }
            authFeedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (authState.isLoggedIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (authState.isLoggedIn) {
                Button(
                    enabled = !controller.isLoading,
                    onClick = { onLogoutProvider(provider) },
                ) {
                    Text(if (controller.isLoading) "退出中" else "退出登录")
                }
                return@Column
            }
            if (supportedLoginModes.size > 1) {
                SingleChoiceSegmentedButtonRow {
                    supportedLoginModes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = activeLoginMode == mode,
                            onClick = { controller.onProviderLoginModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = supportedLoginModes.size),
                            colors = settingsSegmentedButtonColors(),
                        ) {
                            Text(mode.label())
                        }
                    }
                }
            }
            when (activeLoginMode) {
                ProviderLoginMode.WebView -> {
                    Button(
                        enabled = !controller.isLoading && provider.loginConfig != null,
                        onClick = { onOpenProviderWebLogin(provider) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "WebView 登录")
                    }
                }
                ProviderLoginMode.Cookie -> {
                    TextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        value = controller.cookieInputFor(provider.providerId),
                        onValueChange = { controller.onProviderCookiesChange(provider.providerId, it) },
                        placeholder = { Text("""{"MUSIC_U":"...","__csrf":"..."}""") },
                        minLines = 4,
                        maxLines = 8,
                    )
                    Button(
                        enabled = !controller.isLoading,
                        onClick = {
                            controller.loginProviderWithCookies(
                                provider.providerId,
                                controller.cookieInputFor(provider.providerId),
                            )
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "登录")
                    }
                }
                ProviderLoginMode.Headers -> {
                    val headerInput = controller.providerHeaderInputFor(provider.providerId)
                    if (provider.providerId == "ytmusic" && onImportYtmusicHeaderFile != null) {
                        Button(
                            enabled = !controller.isLoading,
                            onClick = onImportYtmusicHeaderFile,
                        ) {
                            Text("导入 ytmusic_header.json")
                        }
                    }
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = headerInput.authorization,
                        onValueChange = { controller.onProviderHeaderAuthorizationChange(provider.providerId, it) },
                        placeholder = { Text("Authorization") },
                        singleLine = true,
                    )
                    TextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        value = headerInput.cookie,
                        onValueChange = { controller.onProviderHeaderCookieChange(provider.providerId, it) },
                        placeholder = { Text("Cookie") },
                        minLines = 4,
                        maxLines = 8,
                    )
                    Button(
                        enabled = !controller.isLoading,
                        onClick = { controller.loginProviderWithHeaders(provider.providerId) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "登录")
                    }
                }
            }
        }
    }
}

fun ProviderLoginMode.label(): String = when (this) {
    ProviderLoginMode.WebView -> "WebView"
    ProviderLoginMode.Cookie -> "复制 Cookie"
    ProviderLoginMode.Headers -> "Headers"
}

@Composable
fun settingsSegmentedButtonColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = MaterialTheme.colorScheme.surface,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
fun settingsFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    containerColor = MaterialTheme.colorScheme.surface,
    labelColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
fun PlaybackPolicySettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
                        colors = settingsSegmentedButtonColors(),
                    ) {
                        Text(policy.label)
                    }
                }
            }
            if (controller.unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace) {
                Text(
                    text = "替换音源",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最低打分",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = formatSmartReplacementScore(controller.smartReplacementMinScore),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = controller.smartReplacementMinScore.toFloat(),
                        onValueChange = {
                            controller.onSmartReplacementMinScoreChange(roundSmartReplacementScore(it.toDouble()))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        enabled = !controller.isLoading,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "使用替换信息",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Checkbox(
                        checked = controller.smartReplacementUseReplacementMetadata,
                        enabled = !controller.isLoading,
                        onCheckedChange = controller::onSmartReplacementUseReplacementMetadataChange,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "使用替换歌词",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Checkbox(
                        checked = controller.smartReplacementUseReplacementLyrics,
                        enabled = !controller.isLoading,
                        onCheckedChange = controller::onSmartReplacementUseReplacementLyricsChange,
                    )
                }
                controller.orderedProviders().forEach { provider ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = provider.providerName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Checkbox(
                            checked = controller.isSmartReplacementProviderEnabled(provider.providerId),
                            enabled = !controller.isLoading,
                            onCheckedChange = {
                                controller.onSmartReplacementProviderEnabledChange(provider.providerId, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerDisplaySettingsPanel(controller: FuoPlayerController) {
    val darkTheme = resolvedDarkTheme(controller.themeMode, isSystemInDarkTheme())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放显示",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
                        colors = settingsSegmentedButtonColors(),
                    ) {
                        Text(size.label)
                    }
                }
            }
            Text(
                text = "频谱样式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PlaybackSpectrumStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = controller.playbackSpectrumStyle == style,
                        onClick = { controller.onPlaybackSpectrumStyleChange(style) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PlaybackSpectrumStyle.entries.size,
                        ),
                        colors = settingsSegmentedButtonColors(),
                    ) {
                        Text(style.label)
                    }
                }
            }
            Text(
                text = "外观模式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = controller.themeMode == mode,
                        onClick = { controller.onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                        colors = settingsSegmentedButtonColors(),
                    ) {
                        Text(mode.label)
                    }
                }
            }
            Text(
                text = "配色方案",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeColorScheme.entries.chunked(2).forEach { rowSchemes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowSchemes.forEach { scheme ->
                            ThemeColorSchemeOption(
                                modifier = Modifier.weight(1f),
                                scheme = scheme,
                                selected = controller.themeColorScheme == scheme,
                                darkTheme = darkTheme,
                                onClick = { controller.onThemeColorSchemeChange(scheme) },
                            )
                        }
                        if (rowSchemes.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeColorSchemeOption(
    modifier: Modifier,
    scheme: ThemeColorScheme,
    selected: Boolean,
    darkTheme: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(themePreviewColor(scheme, darkTheme)),
        )
        Text(
            modifier = Modifier.weight(1f),
            text = scheme.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AudioQualitySettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "音质",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AudioQualityRow(
                title = "WiFi",
                selected = controller.wifiAudioQualityPolicy,
                onSelect = controller::onWifiAudioQualityPolicyChange,
            )
            AudioQualityRow(
                title = "蜂窝网络",
                selected = controller.cellularAudioQualityPolicy,
                onSelect = controller::onCellularAudioQualityPolicyChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQualityRow(
    title: String,
    selected: AudioQualityPolicy,
    onSelect: (AudioQualityPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AudioQualityPolicy.entries.forEachIndexed { index, policy ->
                SegmentedButton(
                    selected = selected == policy,
                    onClick = { onSelect(policy) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AudioQualityPolicy.entries.size),
                    colors = settingsSegmentedButtonColors(),
                ) {
                    Text(policy.label)
                }
            }
        }
    }
}

@Composable
fun LocalMusicScanSettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "本地音乐扫描",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (controller.localMusicDirectories.isEmpty()) {
                Text(
                    text = "授权并扫描本地音乐后显示媒体库目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                controller.localMusicDirectories.forEach { directory ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.onLocalMusicDirectoryEnabledChange(
                                    directory.id,
                                    directory.id in controller.excludedLocalMusicDirectoryIds,
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = directory.id !in controller.excludedLocalMusicDirectoryIds,
                            onCheckedChange = {
                                controller.onLocalMusicDirectoryEnabledChange(directory.id, it)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = directory.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${directory.trackCount} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            LocalMusicDurationFilterRow(
                selected = controller.localMusicMinDurationSeconds,
                onSelect = controller::onLocalMusicMinDurationChange,
            )
        }
    }
}

@Composable
fun LocalMusicDurationFilterRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(0, 10, 30, 60, 90, 120)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (selected > 0) "过滤短音频：小于 ${selected} 秒" else "过滤短音频：不过滤",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { seconds ->
                FilterChip(
                    selected = selected == seconds,
                    onClick = { onSelect(seconds) },
                    label = { Text(if (seconds == 0) "不过滤" else "${seconds} 秒") },
                    colors = settingsFilterChipColors(),
                )
            }
        }
    }
}

@Composable
fun CacheSettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "缓存",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "音乐 ${formatBytes(controller.cacheUsage.audioBytes)} · 图片 ${formatBytes(controller.cacheUsage.imageBytes)} · 总计 ${formatBytes(controller.cacheUsage.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CacheLimitRow(
                title = "音乐缓存",
                selected = controller.audioCacheLimitMb,
                options = listOf(128, 256, 512, 1024, 2048),
                onSelect = controller::onAudioCacheLimitChange,
            )
            CacheLimitRow(
                title = "图片缓存",
                selected = controller.imageCacheLimitMb,
                options = listOf(32, 64, 128, 256, 512),
                onSelect = controller::onImageCacheLimitChange,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::refreshResourceCacheUsage) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("刷新")
                }
                Button(
                    enabled = !controller.isLoading,
                    onClick = controller::clearResourceCache,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("清空缓存")
                }
            }
        }
    }
}

@Composable
fun DebugSettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = controller::openDebugLogs),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.BugReport, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "应用日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DebugLogScreen(controller: FuoPlayerController) {
    val clipboardManager = LocalClipboardManager.current
    var selectionMode by remember { mutableStateOf(false) }
    var selectedLineIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val visibleLogLines = remember(controller.debugLogLines, controller.debugLogLevelFilters) {
        controller.debugLogLines.mapIndexedNotNull { index, line ->
            val level = parseDebugLogLevel(line) ?: DebugLogLevel.Info
            if (level in controller.debugLogLevelFilters) index to line else null
        }
    }
    val selectedLines = remember(controller.debugLogLines, selectedLineIndexes) {
        selectedLineIndexes.sorted().mapNotNull { controller.debugLogLines.getOrNull(it) }
    }
    LaunchedEffect(controller.debugLogLines) {
        selectedLineIndexes = selectedLineIndexes.filter { it in controller.debugLogLines.indices }.toSet()
    }
    LaunchedEffect(selectedLineIndexes) {
        if (selectedLineIndexes.isEmpty()) {
            selectionMode = false
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = controller::closeDebugLogs) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !controller.isLoading,
                        onClick = controller::refreshDebugLogs,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
            controller.debugLogError?.let { ProviderContentMessage(it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DebugLogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level in controller.debugLogLevelFilters,
                        onClick = {
                            controller.onDebugLogLevelFilterChange(
                                level,
                                level !in controller.debugLogLevelFilters,
                            )
                        },
                        label = { Text(debugLogLevelLabel(level)) },
                    )
                }
            }
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 ${selectedLineIndexes.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = visibleLogLines.isNotEmpty(),
                        onClick = {
                            val visibleIndexes = visibleLogLines.map { it.first }.toSet()
                            selectedLineIndexes = if (visibleIndexes.all { it in selectedLineIndexes }) {
                                selectedLineIndexes - visibleIndexes
                            } else {
                                selectedLineIndexes + visibleIndexes
                            }
                        },
                    ) {
                        Text("全选")
                    }
                    TextButton(
                        enabled = selectedLines.isNotEmpty(),
                        onClick = {
                            clipboardManager.setText(AnnotatedString(selectedLines.joinToString(separator = "\n")))
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("复制所选")
                    }
                    TextButton(
                        enabled = selectedLines.isNotEmpty() && !controller.isLoading,
                        onClick = { controller.exportDebugLogs(selectedLines) },
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("导出所选")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (visibleLogLines.isEmpty() && !controller.isLoading && controller.debugLogError == null) {
                    item {
                        ProviderContentMessage("暂无日志")
                    }
                } else {
                    itemsIndexed(visibleLogLines) { _, indexedLine ->
                        val originalIndex = indexedLine.first
                        val line = indexedLine.second
                        val level = parseDebugLogLevel(line)
                        val selected = originalIndex in selectedLineIndexes
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedLineIndexes = if (selected) {
                                                selectedLineIndexes - originalIndex
                                            } else {
                                                selectedLineIndexes + originalIndex
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedLineIndexes = selectedLineIndexes + originalIndex
                                    },
                                ),
                            color = debugLogLevelContainerColor(level),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            selectedLineIndexes = if (checked) {
                                                selectedLineIndexes + originalIndex
                                            } else {
                                                selectedLineIndexes - originalIndex
                                            }
                                        },
                                    )
                                }
                                Text(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 10.dp),
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = debugLogLevelContentColor(level),
                                )
                                IconButton(
                                    modifier = Modifier.size(40.dp),
                                    onClick = { clipboardManager.setText(AnnotatedString(line)) },
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun parseDebugLogLevel(line: String): DebugLogLevel? {
    val code = DEBUG_LOG_THREADTIME_LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)
        ?: DEBUG_LOG_TIME_LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)
        ?: return null
    return when (code) {
        "D" -> DebugLogLevel.Debug
        "I" -> DebugLogLevel.Info
        "W" -> DebugLogLevel.Warning
        "E", "A", "F" -> DebugLogLevel.Error
        else -> null
    }
}

private fun debugLogLevelLabel(level: DebugLogLevel): String = when (level) {
    DebugLogLevel.Debug -> "Debug"
    DebugLogLevel.Info -> "Info"
    DebugLogLevel.Warning -> "Warn"
    DebugLogLevel.Error -> "Error"
}

@Composable
private fun debugLogLevelContainerColor(level: DebugLogLevel?) = when (level) {
    DebugLogLevel.Debug -> MaterialTheme.colorScheme.surfaceVariant
    DebugLogLevel.Info -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f)
    DebugLogLevel.Warning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
    DebugLogLevel.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    null -> MaterialTheme.colorScheme.surface
}

@Composable
private fun debugLogLevelContentColor(level: DebugLogLevel?) = when (level) {
    DebugLogLevel.Error -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun CacheLimitRow(
    title: String,
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$title：${formatCacheLimit(selected)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(formatCacheLimit(value)) },
                )
            }
        }
    }
}

@Composable
fun PermissionPanel(onRequestAudioPermission: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "需要音频权限来扫描系统音乐库",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequestAudioPermission) {
                Text("授权")
            }
        }
    }
}
