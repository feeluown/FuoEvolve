package org.feeluown.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private enum class SettingsPage {
    Main,
    Theme,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenV2(
    controller: FuoPlayerController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    appVersionInfo: String?,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
) {
    val loginProviderId = controller.settingsLoginProviderId
    val loginProvider = controller.orderedProviders().firstOrNull { it.providerId == loginProviderId }
    val layoutInfo = LocalAppLayoutInfo.current
    var page by remember { mutableStateOf(SettingsPage.Main) }

    LaunchedEffect(loginProviderId, controller.providers) {
        if (loginProviderId != null && loginProvider == null) {
            controller.closeSettingsProviderLogin()
        }
        if (loginProviderId != null) {
            page = SettingsPage.Main
        }
    }

    val title = when {
        loginProvider != null -> loginProvider.providerName
        page == SettingsPage.Theme -> "主题设置"
        else -> "设置"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                loginProvider != null -> controller.closeSettingsProviderLogin()
                                page == SettingsPage.Theme -> page = SettingsPage.Main
                                else -> controller.closeSettings()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        when {
            loginProvider != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    ProviderLoginPanel(
                        controller = controller,
                        provider = loginProvider,
                        onOpenProviderWebLogin = onOpenProviderWebLogin,
                        onLogoutProvider = onLogoutProvider,
                        onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                        onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                        onStartYtmusicOAuth = onStartYtmusicOAuth,
                    )
                }
            }

            page == SettingsPage.Theme -> {
                ThemeSettingsContent(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }

            layoutInfo.useWideLayout -> {
                val wideScrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(FuoSpacing.lg)
                        .verticalScroll(wideScrollState),
                    horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                    ) {
                        ProviderSwitchPanel(
                            controller = controller,
                            onOpenProviderLogin = { provider ->
                                controller.openSettingsProviderLogin(provider.providerId)
                            },
                        )
                        AudioQualitySettingsPanel(controller)
                        PlaybackPolicySettingsPanel(controller)
                        SmartReplacementSettingsPanel(controller)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                    ) {
                        ThemeSettingsEntryPanel(
                            controller = controller,
                            onClick = { page = SettingsPage.Theme },
                        )
                        PlayerDisplaySettingsPanelV2(controller)
                        LocalMusicScanSettingsPanel(controller)
                        DownloadSettingsPanel(controller)
                        CacheSettingsPanel(controller)
                        if (controller.isDebugLogViewerAvailable) {
                            DebugSettingsPanel(controller)
                        }
                        AppInfoPanel(appVersionInfo)
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    ProviderSwitchPanel(
                        controller = controller,
                        onOpenProviderLogin = { provider ->
                            controller.openSettingsProviderLogin(provider.providerId)
                        },
                    )
                    AudioQualitySettingsPanel(controller)
                    PlaybackPolicySettingsPanel(controller)
                    SmartReplacementSettingsPanel(controller)
                    ThemeSettingsEntryPanel(
                        controller = controller,
                        onClick = { page = SettingsPage.Theme },
                    )
                    PlayerDisplaySettingsPanelV2(controller)
                    LocalMusicScanSettingsPanel(controller)
                    DownloadSettingsPanel(controller)
                    CacheSettingsPanel(controller)
                    if (controller.isDebugLogViewerAvailable) {
                        DebugSettingsPanel(controller)
                    }
                    AppInfoPanel(appVersionInfo)
                }
            }
        }
    }
}

@Composable
private fun ThemeSettingsEntryPanel(
    controller: FuoPlayerController,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        FuoSettingRow(
            modifier = Modifier.fillMaxWidth(),
            title = "主题设置",
            supportingText = "${controller.themeMode.label} · ${controller.themeColorScheme.label}",
            onClick = onClick,
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
        )
    }
}

@Composable
private fun PlayerDisplaySettingsPanelV2(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放显示",
                style = MaterialTheme.typography.titleMedium,
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
            if (controller.isStatusBarLyricsAvailable) {
                FuoSettingRow(
                    modifier = Modifier.fillMaxWidth(),
                    title = "开启状态栏歌词",
                    supportingText = "通过词幕在系统状态栏显示当前歌词",
                    enabled = !controller.isLoading,
                    onClick = {
                        controller.onStatusBarLyricsEnabledChange(!controller.statusBarLyricsEnabled)
                    },
                    trailingContent = {
                        Switch(
                            checked = controller.statusBarLyricsEnabled,
                            enabled = !controller.isLoading,
                            onCheckedChange = controller::onStatusBarLyricsEnabledChange,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeSettingsContent(
    controller: FuoPlayerController,
    modifier: Modifier = Modifier,
) {
    val darkTheme = resolvedDarkTheme(controller.themeMode, isSystemInDarkTheme())
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(FuoSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
    ) {
        ThemeSelectorCard(
            title = "主题",
            supportingText = "选择应用的主题模式",
            value = controller.themeMode.label,
            icon = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
            options = ThemeMode.entries,
            selected = controller.themeMode,
            optionLabel = ThemeMode::label,
            enabled = !controller.isLoading,
            onSelect = controller::onThemeModeChange,
        )
        ThemeSelectorCard(
            title = "强调色",
            supportingText = "选择 Material 3 主题使用的配色种子",
            value = controller.themeColorScheme.label,
            icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
            options = ThemeColorScheme.entries,
            selected = controller.themeColorScheme,
            optionLabel = ThemeColorScheme::label,
            enabled = !controller.isLoading,
            optionLeading = { scheme ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(themePreviewColor(scheme, darkTheme)),
                )
            },
            onSelect = controller::onThemeColorSchemeChange,
        )
        ThemeSwitchCard(
            title = "封面动态取色",
            supportingText = "根据当前播放封面生成播放器主题色",
            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
            checked = controller.dynamicCoverColorEnabled,
            enabled = !controller.isLoading,
            onCheckedChange = controller::onDynamicCoverColorEnabledChange,
        )
        Text(
            modifier = Modifier.padding(horizontal = FuoSpacing.md, vertical = FuoSpacing.sm),
            text = "动态取色只影响播放页；封面不可用时自动回退到上方选择的强调色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ThemeSelectorCard(
    title: String,
    supportingText: String,
    value: String,
    icon: @Composable () -> Unit,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    optionLeading: (@Composable (T) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            FuoSettingRow(
                modifier = Modifier.fillMaxWidth(),
                title = title,
                supportingText = supportingText,
                enabled = enabled,
                onClick = { expanded = true },
                leadingContent = {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        icon()
                    }
                },
                trailingContent = {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                val isSelected = option == selected
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
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else if (optionLeading != null) {
                            optionLeading(option)
                        } else {
                            Box(Modifier.size(24.dp))
                        }
                    },
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
private fun ThemeSwitchCard(
    title: String,
    supportingText: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        FuoSettingRow(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            supportingText = supportingText,
            enabled = enabled,
            onClick = { onCheckedChange(!checked) },
            leadingContent = {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
}
