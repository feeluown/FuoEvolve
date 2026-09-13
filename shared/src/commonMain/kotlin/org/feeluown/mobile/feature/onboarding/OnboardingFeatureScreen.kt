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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import org.feeluown.mobile.feature.onboarding.OnboardingFeedbackKind

private const val ONBOARDING_PAGE_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFeatureScreen(
    onboarding: OnboardingFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    providerAuth: ProviderAuthFeatureController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)? = null,
    onImportYtmusicOAuthFile: (() -> Unit)? = null,
    onStartYtmusicOAuth: (() -> Unit)? = null,
) {
    val onboardingState by onboarding.uiState.collectAsStateWithLifecycle()
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
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val sourcePage = pagerState.currentPage == 0
    val replacementPage = pagerState.currentPage == 1
    val accountPage = pagerState.currentPage == 2
    val ytmusicOAuthFlowActive = authState.ytmusicOAuthFlow != null
    val authBusy = accountPage && selectedProviders.any { provider ->
        providerAuth.isBusy(provider.providerId) &&
            !(provider.providerId == "ytmusic" && ytmusicOAuthFlowActive)
    }
    val busy = onboardingState.isBusy || catalogState.isLoading || authBusy
    val allLoggedIn = selectedProviders.isNotEmpty() && selectedProviders.all { provider ->
        providerAuth.authStateFor(provider).isLoggedIn
    }
    val actionEnabled = when {
        sourcePage -> availableProviders.isNotEmpty() && onboardingState.selectedProviderIds.isNotEmpty()
        replacementPage -> onboardingState.contentProviderIds.isNotEmpty() &&
            (!onboardingState.smartReplacementEnabled || onboardingState.replacementProviderIds.isNotEmpty())
        else -> true
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("初始设置") },
                navigationIcon = {
                    if (!sourcePage) {
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                if (accountPage && ytmusicOAuthFlowActive) {
                                    providerAuth.cancelYtmusicTvOAuthLogin()
                                }
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            },
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
                pageCount = ONBOARDING_PAGE_COUNT,
                isBusy = busy,
                actionEnabled = actionEnabled,
                actionLabel = when {
                    accountPage && allLoggedIn -> "开始使用"
                    accountPage -> "稍后登录"
                    else -> "继续"
                },
                onAction = {
                    when {
                        sourcePage -> scope.launch { pagerState.animateScrollToPage(1) }
                        replacementPage -> onboarding.applyProviderConfiguration { success ->
                            if (success) scope.launch { pagerState.animateScrollToPage(2) }
                        }
                        else -> {
                            if (ytmusicOAuthFlowActive) {
                                providerAuth.cancelYtmusicTvOAuthLogin()
                            }
                            onboarding.complete()
                        }
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            userScrollEnabled = false,
            verticalAlignment = Alignment.Top,
        ) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when (page) {
                    0 -> OnboardingProviderSelectionPage(
                        providers = availableProviders,
                        state = onboardingState,
                        catalogState = catalogState,
                        enabled = !busy,
                        onProviderSelected = onboarding::setProviderSelected,
                        onRetry = providerCatalog::refresh,
                    )
                    1 -> OnboardingReplacementPage(
                        providers = selectedProviders,
                        state = onboardingState,
                        enabled = !busy,
                        onContentProviderEnabled = onboarding::setContentProviderEnabled,
                        onReplacementProviderEnabled = onboarding::setReplacementProviderEnabled,
                        onSmartReplacementEnabled = onboarding::setSmartReplacementEnabled,
                        onSmartReplacementMinScore = onboarding::setSmartReplacementMinScore,
                    )
                    else -> OnboardingAccountsPage(
                        providers = selectedProviders,
                        state = onboardingState,
                        enabled = !busy,
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

@Composable
private fun OnboardingProviderSelectionPage(
    providers: List<ProviderInfo>,
    state: OnboardingUiState,
    catalogState: ProviderCatalogUiState,
    enabled: Boolean,
    onProviderSelected: (String, Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("选择音乐来源", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "选择希望 FuoEvolve 使用的音乐服务，之后仍可在音源管理中修改。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            catalogState.errorMessage != null && providers.isEmpty() -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("音源初始化失败", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            catalogState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(onClick = onRetry) { Text("重新初始化") }
                    }
                }
            }
            providers.isEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("正在发现可用音源")
                }
            }
            else -> providers.forEach { provider ->
                val selected = provider.providerId in state.selectedProviderIds
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) {
                        onProviderSelected(provider.providerId, !selected)
                    },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider.providerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (selected) "已选择" else "点按添加此音源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        OnboardingFeedbackText(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingReplacementPage(
    providers: List<ProviderInfo>,
    state: OnboardingUiState,
    enabled: Boolean,
    onContentProviderEnabled: (String, Boolean) -> Unit,
    onReplacementProviderEnabled: (String, Boolean) -> Unit,
    onSmartReplacementEnabled: (Boolean) -> Unit,
    onSmartReplacementMinScore: (Double) -> Unit,
) {
    val presets = listOf(
        "宽松" to 0.45,
        "平衡" to DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        "严格" to 0.70,
    )
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("配置智能替换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "当前音源无法播放时，可以自动从其他音源寻找匹配版本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(true to "智能替换", false to "跳过").forEachIndexed { index, (smart, label) ->
                SegmentedButton(
                    selected = state.smartReplacementEnabled == smart,
                    onClick = { onSmartReplacementEnabled(smart) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    label = { Text(label) },
                )
            }
        }

        Text("音源角色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "常规音源用于首页、搜索与个人内容；替代音源只在智能替换时参与匹配。一个音源可以同时承担两种角色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        providers.forEach { provider ->
            val contentEnabled = provider.providerId in state.contentProviderIds
            val replacementEnabled = provider.providerId in state.replacementProviderIds
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(provider.providerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OnboardingRoleRow(
                        title = "用于常规内容",
                        supportingText = "参与首页、搜索、推荐和我的内容",
                        checked = contentEnabled,
                        enabled = enabled && (!contentEnabled || state.contentProviderIds.size > 1),
                        onCheckedChange = { onContentProviderEnabled(provider.providerId, it) },
                    )
                    if (state.smartReplacementEnabled) {
                        OnboardingRoleRow(
                            title = "用于智能替换",
                            supportingText = "其他音源资源不可用时作为候选来源",
                            checked = replacementEnabled,
                            enabled = enabled && (!replacementEnabled || state.replacementProviderIds.size > 1),
                            onCheckedChange = { onReplacementProviderEnabled(provider.providerId, it) },
                        )
                    }
                }
            }
        }

        if (state.smartReplacementEnabled) {
            Text("匹配精度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { (label, score) ->
                    FilterChip(
                        selected = kotlin.math.abs(state.smartReplacementMinScore - score) < 0.001,
                        onClick = { onSmartReplacementMinScore(score) },
                        enabled = enabled,
                        label = { Text(label) },
                    )
                }
            }
            Text(
                when {
                    state.smartReplacementMinScore < DEFAULT_SMART_REPLACEMENT_MIN_SCORE -> "宽松：提高匹配成功率，可能接受更多版本差异。"
                    state.smartReplacementMinScore > DEFAULT_SMART_REPLACEMENT_MIN_SCORE -> "严格：优先保证版本准确性，可能减少替换成功率。"
                    else -> "平衡：兼顾匹配成功率和版本准确性，推荐使用。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OnboardingFeedbackText(state)
    }
}

@Composable
private fun OnboardingRoleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title)
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OnboardingAccountsPage(
    providers: List<ProviderInfo>,
    state: OnboardingUiState,
    enabled: Boolean,
    authController: ProviderAuthFeatureController,
    authState: ProviderAuthUiState,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
    onImportYtmusicOAuthFile: (() -> Unit)?,
    onStartYtmusicOAuth: (() -> Unit)?,
) {
    var expandedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("连接账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "登录后可使用个性化推荐、歌单和账号内容；未完成的账号也可以稍后在音源管理中登录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        providers.forEach { provider ->
            val loggedIn = authController.authStateFor(provider).isLoggedIn
            OnboardingProviderAccountCard(
                provider = provider,
                enabled = enabled,
                authController = authController,
                authState = authState,
                expanded = !loggedIn && expandedProviderId == provider.providerId,
                onExpandedChange = { expanded -> expandedProviderId = provider.providerId.takeIf { expanded } },
                onOpenProviderWebLogin = onOpenProviderWebLogin,
                onLogoutProvider = onLogoutProvider,
                onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                onStartYtmusicOAuth = onStartYtmusicOAuth,
            )
        }
        authState.feedback?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OnboardingFeedbackText(state)
    }
}

@Composable
private fun OnboardingProviderAccountCard(
    provider: ProviderInfo,
    enabled: Boolean,
    authController: ProviderAuthFeatureController,
    authState: ProviderAuthUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
    onImportYtmusicOAuthFile: (() -> Unit)?,
    onStartYtmusicOAuth: (() -> Unit)?,
) {
    val currentAuth = authController.authStateFor(provider)
    val oauthFlowActive = provider.providerId == "ytmusic" && authState.ytmusicOAuthFlow != null
    val busy = authController.isBusy(provider.providerId)
    val operationInteractive = enabled && !busy
    val cardInteractive = operationInteractive || (enabled && oauthFlowActive)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (currentAuth.isLoggedIn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(provider.providerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (currentAuth.isLoggedIn) {
                            currentAuth.userName?.takeIf { it.isNotBlank() }?.let { "已连接 · $it" } ?: "已连接"
                        } else {
                            "尚未连接"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (currentAuth.isLoggedIn) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "已连接", tint = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = { onExpandedChange(!expanded) }, enabled = cardInteractive) {
                        Text(if (expanded) "收起" else "登录")
                    }
                }
            }
            if (currentAuth.isLoggedIn) {
                TextButton(onClick = { onLogoutProvider(provider) }, enabled = operationInteractive) { Text("退出登录") }
            } else if (expanded) {
                OnboardingProviderLoginControls(
                    provider = provider,
                    enabled = enabled,
                    authController = authController,
                    authState = authState,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onImportYtmusicHeaderFile = onImportYtmusicHeaderFile,
                    onImportYtmusicOAuthFile = onImportYtmusicOAuthFile,
                    onStartYtmusicOAuth = onStartYtmusicOAuth,
                )
            }
        }
    }
}

@Composable
private fun OnboardingProviderLoginControls(
    provider: ProviderInfo,
    enabled: Boolean,
    authController: ProviderAuthFeatureController,
    authState: ProviderAuthUiState,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onImportYtmusicHeaderFile: (() -> Unit)?,
    onImportYtmusicOAuthFile: (() -> Unit)?,
    onStartYtmusicOAuth: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val busy = authController.isBusy(provider.providerId)
    val operationInteractive = enabled && !busy
    val modes = provider.supportedLoginModes.toList().ifEmpty { listOf(ProviderLoginMode.Cookie) }
    var selectedMode by rememberSaveable(provider.providerId) { mutableStateOf(modes.first()) }
    val header = authController.headerInput(provider.providerId)
    val oauth = authController.oauthInput(provider.providerId)
    val oauthFlow = authState.ytmusicOAuthFlow.takeIf { provider.providerId == "ytmusic" }
    val oauthFlowInteractive = enabled && oauthFlow != null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (modes.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        enabled = operationInteractive,
                        onClick = { selectedMode = mode },
                        label = { Text(onboardingLoginModeLabel(mode)) },
                    )
                }
            }
        }
        when (selectedMode) {
            ProviderLoginMode.WebView -> Button(
                onClick = { onOpenProviderWebLogin(provider) },
                enabled = provider.loginConfig != null && operationInteractive,
            ) { Text("网页登录") }
            ProviderLoginMode.Cookie -> {
                OutlinedTextField(
                    value = authController.cookieInput(provider.providerId),
                    onValueChange = { authController.onCookiesChange(provider.providerId, it) },
                    label = { Text("Cookie / Cookie JSON") },
                    minLines = 3,
                    enabled = operationInteractive,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { authController.loginWithCookies(provider.providerId, authController.cookieInput(provider.providerId)) },
                    enabled = operationInteractive,
                ) { Text("使用 Cookie 登录") }
            }
            ProviderLoginMode.Headers -> {
                OutlinedTextField(
                    value = header.authorization,
                    onValueChange = { authController.onHeaderAuthorizationChange(provider.providerId, it) },
                    label = { Text("Authorization") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = operationInteractive,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = header.cookie,
                    onValueChange = { authController.onHeaderCookieChange(provider.providerId, it) },
                    label = { Text("Cookie") },
                    minLines = 2,
                    enabled = operationInteractive,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { authController.loginWithHeaders(provider.providerId) }, enabled = operationInteractive) {
                    Text("使用 Headers 登录")
                }
                if (provider.providerId == "ytmusic") {
                    onImportYtmusicHeaderFile?.let { action ->
                        TextButton(onClick = action, enabled = operationInteractive) { Text("导入 ytmusic_header.json") }
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
                    enabled = operationInteractive && oauthFlow == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = oauth.clientSecret,
                    onValueChange = { authController.onOAuthClientSecretChange(provider.providerId, it) },
                    label = { Text("client_secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = operationInteractive && oauthFlow == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (oauthFlow == null) {
                    val startAction = onStartYtmusicOAuth ?: authController::startYtmusicTvOAuthLogin
                    Button(onClick = startAction, enabled = operationInteractive) { Text("使用 Google 登录（TV）") }
                    onImportYtmusicOAuthFile?.let { action ->
                        TextButton(onClick = action, enabled = operationInteractive) { Text("导入 client_secret.json / oauth.json") }
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
                                OutlinedButton(
                                    onClick = authController::copyYtmusicOAuthUserCode,
                                    enabled = oauthFlowInteractive,
                                ) { Text("复制验证码") }
                                OutlinedButton(
                                    enabled = oauthFlowInteractive && verificationUrl.isNotBlank(),
                                    onClick = {
                                        runCatching { uriHandler.openUri(verificationUrl) }
                                            .onSuccess { authController.markYtmusicOAuthBrowserOpened() }
                                    },
                                ) { Text("重新打开浏览器") }
                            }
                        }
                    }
                    Text(verificationUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = authController::cancelYtmusicTvOAuthLogin,
                        enabled = oauthFlowInteractive,
                    ) { Text("取消授权") }
                }
            }
        }
        authController.authError(provider.providerId)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun OnboardingFeedbackText(state: OnboardingUiState) {
    state.feedback?.let { feedback ->
        Text(
            feedback.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (feedback.kind == OnboardingFeedbackKind.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun OnboardingFeatureFooter(
    currentPage: Int,
    pageCount: Int,
    isBusy: Boolean,
    actionEnabled: Boolean,
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
            Button(onClick = onAction, enabled = actionEnabled && !isBusy) {
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
