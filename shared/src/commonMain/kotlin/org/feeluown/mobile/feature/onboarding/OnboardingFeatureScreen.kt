package org.feeluown.mobile

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFeatureScreen(
    onboarding: OnboardingFeatureController,
    settings: SettingsFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    providerAuth: ProviderAuthFeatureController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
) {
    val onboardingState by onboarding.uiState.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val catalogState by providerCatalog.uiState.collectAsStateWithLifecycle()
    val authState by providerAuth.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(catalogState.availableProviders) {
        onboarding.initialize(catalogState)
    }
    val availableProviders = remember(catalogState.availableProviders, catalogState.providerOrderIds) {
        val order = catalogState.providerOrderIds.withIndex().associate { it.value to it.index }
        catalogState.availableProviders.sortedBy { order[it.providerId] ?: Int.MAX_VALUE }
    }
    val selectedProviders = remember(availableProviders, onboardingState.selectedProviderIds) {
        availableProviders.filter { it.providerId in onboardingState.selectedProviderIds }
    }
    val pageCount = selectedProviders.size + 3
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val sourcePage = pagerState.currentPage == 0
    val themePage = pagerState.currentPage == pageCount - 2
    val qualityPage = pagerState.currentPage == pageCount - 1
    val busy = onboardingState.isBusy || catalogState.isLoading

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
                    if (!sourcePage) {
                        IconButton(
                            enabled = !busy,
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一步")
                        }
                    }
                },
            )
        },
        bottomBar = {
            OnboardingFeatureFooter(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                isBusy = busy,
                actionLabel = when {
                    sourcePage || themePage -> "继续"
                    qualityPage -> "完成"
                    else -> {
                        val provider = selectedProviders.getOrNull(pagerState.currentPage - 1)
                        if (provider != null && providerAuth.authStateFor(provider).isLoggedIn) "继续" else "跳过"
                    }
                },
                onAction = {
                    when {
                        sourcePage -> onboarding.applyProviderSelection { success ->
                            if (success) scope.launch { pagerState.animateScrollToPage(1) }
                        }
                        qualityPage -> onboarding.complete()
                        else -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            userScrollEnabled = !busy && !sourcePage,
            verticalAlignment = Alignment.Top,
        ) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    page == 0 -> OnboardingProviderSelectionPage(
                        providers = availableProviders,
                        state = onboardingState,
                        enabled = !busy,
                        onProviderSelected = onboarding::setProviderSelected,
                        onReplacementOnlyChange = onboarding::setBilibiliReplacementOnly,
                    )
                    page == pageCount - 2 -> OnboardingThemePage(
                        settingsState = settingsState,
                        settingsController = settings,
                    )
                    page == pageCount - 1 -> OnboardingQualityPage(
                        settingsState = settingsState,
                        settingsController = settings,
                    )
                    else -> selectedProviders.getOrNull(page - 1)?.let { provider ->
                        OnboardingProviderLoginPage(
                            provider = provider,
                            authController = providerAuth,
                            authState = authState,
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

@Composable
private fun OnboardingProviderSelectionPage(
    providers: List<ProviderInfo>,
    state: OnboardingUiState,
    enabled: Boolean,
    onProviderSelected: (String, Boolean) -> Unit,
    onReplacementOnlyChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("选择要启用的音源", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "至少选择一个音源，之后可以逐一登录；这些设置也可以稍后在设置中修改。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (providers.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("音源正在初始化")
            }
        } else {
            providers.forEach { provider ->
                val selected = provider.providerId in state.selectedProviderIds
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) {
                        onProviderSelected(provider.providerId, !selected)
                    },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            enabled = enabled,
                            onCheckedChange = { onProviderSelected(provider.providerId, it) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(provider.providerName, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (selected) "将启用此音源" else "不会加载此音源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if ("bilibili" in state.selectedProviderIds) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) {
                    onReplacementOnlyChange(!state.bilibiliReplacementOnly)
                },
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.bilibiliReplacementOnly,
                        enabled = enabled,
                        onCheckedChange = onReplacementOnlyChange,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Bilibili 仅作为替换音源", fontWeight = FontWeight.SemiBold)
                        Text(
                            "不在搜索和首页展示，只在原音源资源不可用时参与智能替换。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        state.feedback?.let { feedback ->
            Text(
                feedback,
                color = if (feedback.contains("失败") || feedback.startsWith("请至少") || feedback.startsWith("Bilibili")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun OnboardingThemePage(
    settingsState: SettingsFeatureUiState,
    settingsController: SettingsFeatureController,
) {
    val appSettings = settingsState.settings
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("选择应用主题", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("之后仍可在设置中修改。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("外观模式", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = appSettings.themeMode == mode,
                    onClick = { settingsController.update { it.copy(themeMode = mode) } },
                    label = { Text(mode.label) },
                )
            }
        }
        Text("配色方案", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeColorScheme.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { scheme ->
                        FilterChip(
                            selected = appSettings.themeColorScheme == scheme,
                            onClick = { settingsController.update { it.copy(themeColorScheme = scheme) } },
                            label = { Text(scheme.label) },
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("封面动态取色")
                Text("根据当前播放封面生成播放器主题色", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = appSettings.dynamicCoverColorEnabled,
                onCheckedChange = { enabled -> settingsController.update { it.copy(dynamicCoverColorEnabled = enabled) } },
            )
        }
    }
}

@Composable
private fun OnboardingQualityPage(
    settingsState: SettingsFeatureUiState,
    settingsController: SettingsFeatureController,
) {
    val appSettings = settingsState.settings
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("选择默认音质", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("可以分别设置 Wi‑Fi 和蜂窝网络下的播放音质。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Wi‑Fi", style = MaterialTheme.typography.titleMedium)
        OnboardingQualityChoices(
            selected = appSettings.wifiAudioQualityPolicy,
            onSelect = settingsController::setWifiAudioQualityPolicy,
        )
        Text("蜂窝网络", style = MaterialTheme.typography.titleMedium)
        OnboardingQualityChoices(
            selected = appSettings.cellularAudioQualityPolicy,
            onSelect = settingsController::setCellularAudioQualityPolicy,
        )
    }
}

@Composable
private fun OnboardingQualityChoices(
    selected: AudioQualityPolicy,
    onSelect: (AudioQualityPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AudioQualityPolicy.entries.forEach { policy ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(policy) },
                color = if (selected == policy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(policy.label, modifier = Modifier.weight(1f))
                    if (selected == policy) Icon(Icons.Filled.CheckCircle, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun OnboardingProviderLoginPage(
    provider: ProviderInfo,
    authController: ProviderAuthFeatureController,
    authState: ProviderAuthUiState,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
    onImportYtmusicOAuthFile: (() -> Unit)?,
    onStartYtmusicOAuth: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val currentAuth = authController.authStateFor(provider)
    val busy = authController.isBusy(provider.providerId)
    val modes = provider.supportedLoginModes.toList().ifEmpty { listOf(ProviderLoginMode.Cookie) }
    var selectedMode by rememberSaveable(provider.providerId) { mutableStateOf(modes.first()) }
    val header = authController.headerInput(provider.providerId)
    val oauth = authController.oauthInput(provider.providerId)
    val oauthFlow = authState.ytmusicOAuthFlow.takeIf { provider.providerId == "ytmusic" }

    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(provider.providerName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (currentAuth.isLoggedIn) {
                currentAuth.userName?.takeIf { it.isNotBlank() }?.let { "已登录：$it" } ?: "已登录"
            } else {
                "登录可使用个性化推荐、我的歌单等功能；也可以先跳过。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (currentAuth.isLoggedIn) {
            OutlinedButton(onClick = { onLogoutProvider(provider) }, enabled = !busy) { Text("退出登录") }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    enabled = !busy,
                    onClick = { selectedMode = mode },
                    label = { Text(onboardingLoginModeLabel(mode)) },
                )
            }
        }
        when (selectedMode) {
            ProviderLoginMode.WebView -> Button(
                onClick = { onOpenProviderWebLogin(provider) },
                enabled = provider.loginConfig != null && !busy,
            ) { Text("网页登录") }
            ProviderLoginMode.Cookie -> {
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
                ) { Text("使用 Cookie 登录") }
            }
            ProviderLoginMode.Headers -> {
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
                Button(onClick = { authController.loginWithHeaders(provider.providerId) }, enabled = !busy) {
                    Text("使用 Headers 登录")
                }
                if (provider.providerId == "ytmusic") {
                    onImportYtmusicHeaderFile?.let { action ->
                        TextButton(onClick = action, enabled = !busy) { Text("导入 ytmusic_header.json") }
                    }
                }
            }
            ProviderLoginMode.OAuth -> {
                Text(
                    "使用 Google Cloud「TVs and Limited Input devices」类型的 OAuth 客户端。",
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
                    Button(onClick = startAction, enabled = !busy) { Text("使用 Google 登录（TV）") }
                    onImportYtmusicOAuthFile?.let { action ->
                        TextButton(onClick = action, enabled = !busy) { Text("导入 client_secret.json / oauth.json") }
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
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("设备验证码", style = MaterialTheme.typography.labelMedium)
                            Text(oauthFlow.userCode, style = MaterialTheme.typography.headlineMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = authController::copyYtmusicOAuthUserCode) { Text("复制验证码") }
                                OutlinedButton(
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
        authController.authError(provider.providerId)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        authState.feedback?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun OnboardingFeatureFooter(
    currentPage: Int,
    pageCount: Int,
    isBusy: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${currentPage + 1} / $pageCount",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAction, enabled = !isBusy) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(actionLabel)
            }
        }
    }
}

private fun onboardingLoginModeLabel(mode: ProviderLoginMode): String = when (mode) {
    ProviderLoginMode.WebView -> "网页"
    ProviderLoginMode.Cookie -> "Cookie"
    ProviderLoginMode.Headers -> "Headers"
    ProviderLoginMode.OAuth -> "OAuth"
}
