package org.feeluown.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private val EXPRESSIVE_PLAYBACK_REPORTING_PROVIDER_IDS = setOf("netease", "bilibili", "ytmusic")

/**
 * Provider landing page: browse providers, inspect their state at a glance and freely reorder them.
 * Per-provider controls intentionally live on the provider detail page.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProviderCatalogSettingsExpressive(
    state: ProviderCatalogUiState,
    controller: ProviderCatalogFeatureController,
    onOpenProvider: (ProviderInfo) -> Unit,
    onOpenCredentialBackup: () -> Unit,
) {
    val credentialBackupActions = LocalProviderCredentialBackupActions.current
    val openSupportedLinksSettings = rememberSupportedLinksSettingsAction()
    val ordered = remember(state.availableProviders, state.providerOrderIds) {
        val order = state.providerOrderIds.withIndex().associate { it.value to it.index }
        state.availableProviders.sortedBy { order[it.providerId] ?: Int.MAX_VALUE }
    }
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { FuoSpacing.sm.toPx() }
    var draggingProviderId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var itemExtentPx by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
        Column(
            modifier = Modifier.padding(horizontal = FuoSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
        ) {
            Text("音源优先级", style = MaterialTheme.typography.titleLarge)
            Text(
                "点按进入音源设置；长按任意卡片后可直接拖到目标位置。排序会影响聚合展示与候选音源优先级。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ordered.forEachIndexed { index, provider ->
            key(provider.providerId) {
                val enabled = provider.providerId in state.enabledProviderIds
                val auth = state.sessions.authStates[provider.providerId]
                val dragging = draggingProviderId == provider.providerId
                val scale by animateFloatAsState(
                    targetValue = if (dragging) 1.025f else 1f,
                    animationSpec = FuoMotion.fastSpatialSpec(),
                    label = "Provider reorder scale",
                )
                val visualOffset = if (dragging && itemExtentPx > 0f && dragStartIndex >= 0) {
                    dragDistancePx - (dragCurrentIndex - dragStartIndex) * itemExtentPx
                } else {
                    0f
                }

                Surface(
                    onClick = {
                        if (draggingProviderId == null && !state.isLoading) onOpenProvider(provider)
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = visualOffset
                            scaleX = scale
                            scaleY = scale
                        }
                        .onSizeChanged { size ->
                            if (size.height > 0) itemExtentPx = size.height.toFloat() + itemSpacingPx
                        }
                        .pointerInput(provider.providerId, state.isLoading, itemExtentPx, ordered.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (!state.isLoading) {
                                        draggingProviderId = provider.providerId
                                        dragStartIndex = index
                                        dragCurrentIndex = index
                                        dragDistancePx = 0f
                                    }
                                },
                                onDragEnd = {
                                    draggingProviderId = null
                                    dragStartIndex = -1
                                    dragCurrentIndex = -1
                                    dragDistancePx = 0f
                                },
                                onDragCancel = {
                                    draggingProviderId = null
                                    dragStartIndex = -1
                                    dragCurrentIndex = -1
                                    dragDistancePx = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (draggingProviderId != provider.providerId || itemExtentPx <= 0f) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    dragDistancePx += amount.y
                                    val targetIndex = providerDropTargetIndex(
                                        startIndex = dragStartIndex,
                                        dragDistancePx = dragDistancePx,
                                        itemExtentPx = itemExtentPx,
                                        lastIndex = ordered.lastIndex,
                                    )
                                    if (targetIndex != dragCurrentIndex) {
                                        controller.moveProvider(provider.providerId, targetIndex - dragCurrentIndex)
                                        dragCurrentIndex = targetIndex
                                    }
                                },
                            )
                        },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = when {
                        dragging -> MaterialTheme.colorScheme.secondaryContainer
                        enabled -> MaterialTheme.colorScheme.surfaceContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    tonalElevation = if (dragging) 6.dp else 0.dp,
                    shadowElevation = if (dragging) 3.dp else 0.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(FuoSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (enabled) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
                        ) {
                            Text(
                                provider.providerName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                expressiveProviderStatusText(enabled, auth),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                expressiveProviderDisplaySummary(state, provider.providerId),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "长按拖动${provider.providerName}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    openSupportedLinksSettings?.let { action ->
        ExpressiveSettingsGroup(title = "系统集成") {
            ExpressiveSettingsRow(
                title = "支持的链接",
                supportingText = "前往系统应用链接设置，按需允许 FuoEvolve 打开各音源链接",
                leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = action,
            )
        }
    }

    if (credentialBackupActions.isAvailable) {
        ExpressiveSettingsGroup(title = "登录凭证") {
            ExpressiveSettingsRow(
                title = "备份与恢复",
                supportingText = "批量导出或恢复已登录音源的凭证",
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProviderDetailSettingsExpressive(
    provider: ProviderInfo,
    catalog: ProviderCatalogUiState,
    catalogController: ProviderCatalogFeatureController,
    settings: SettingsFeatureUiState,
    settingsController: SettingsFeatureController,
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
    val openSupportedLinksSettings = rememberSupportedLinksSettingsAction()
    val auth = authController.authStateFor(provider)
    val authBusy = authController.isBusy(provider.providerId)
    val busy = authBusy || catalog.isLoading || settings.isBusy
    val enabled = provider.providerId in catalog.enabledProviderIds
    val canToggleEnabled = !busy && (!enabled || catalog.enabledProviderIds.size > 1)
    val modes = provider.supportedLoginModes.toList().ifEmpty { listOf(ProviderLoginMode.Cookie) }
    var mode by remember(provider.providerId, modes) { mutableStateOf(modes.first()) }
    val header = authController.headerInput(provider.providerId)
    val oauth = authController.oauthInput(provider.providerId)
    val oauthFlow = authUiState.ytmusicOAuthFlow.takeIf { provider.providerId == "ytmusic" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(FuoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs)) {
                Text(provider.providerName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    expressiveProviderStatusText(enabled, auth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TonalToggleButton(
                checked = enabled,
                onCheckedChange = { catalogController.setProviderEnabled(provider.providerId, it) },
                enabled = canToggleEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                )
                Spacer(Modifier.width(FuoSpacing.sm))
                Text(if (enabled) "音源已启用" else "启用此音源")
            }
            if (enabled && catalog.enabledProviderIds.size <= 1) {
                Text(
                    "至少需要保留一个启用音源，因此当前音源暂不能关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    ExpressiveProviderDisplaySettings(
        state = catalog,
        controller = catalogController,
        provider = provider,
        enabled = !busy,
    )

    ExpressiveSettingsGroup(title = "账号") {
        ExpressiveSettingsRow(
            title = if (auth.isLoggedIn) "已登录" else "未登录",
            supportingText = auth.userName.orEmpty().ifBlank { provider.providerName },
            leadingContent = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
        )
        if (auth.isLoggedIn) {
            Box(Modifier.fillMaxWidth().padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md)) {
                OutlinedButton(
                    onClick = { onLogoutProvider(provider) },
                    enabled = !authBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("退出登录") }
            }
        }
    }

    if (!auth.isLoggedIn) {
        ExpressiveSettingsGroup(title = "登录方式") {
            if (modes.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
                    modes.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = mode == candidate,
                            enabled = !authBusy,
                            onClick = { mode = candidate },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(expressiveProviderLoginModeLabel(candidate)) }
                    }
                }
            }
            when (mode) {
                ProviderLoginMode.WebView -> Box(Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
                    Button(
                        onClick = { onOpenProviderWebLogin(provider) },
                        enabled = provider.loginConfig != null && !authBusy,
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
                        enabled = !authBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            authController.loginWithCookies(
                                provider.providerId,
                                authController.cookieInput(provider.providerId),
                            )
                        },
                        enabled = !authBusy,
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
                        enabled = !authBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = header.cookie,
                        onValueChange = { authController.onHeaderCookieChange(provider.providerId, it) },
                        label = { Text("Cookie") },
                        minLines = 2,
                        enabled = !authBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { authController.loginWithHeaders(provider.providerId) },
                        enabled = !authBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("使用 Headers 登录") }
                    if (provider.providerId == "ytmusic") {
                        onImportYtmusicHeaderFile?.let { action ->
                            OutlinedButton(onClick = action, enabled = !authBusy, modifier = Modifier.fillMaxWidth()) {
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
                        enabled = !authBusy && oauthFlow == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = oauth.clientSecret,
                        onValueChange = { authController.onOAuthClientSecretChange(provider.providerId, it) },
                        label = { Text("client_secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !authBusy && oauthFlow == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (oauthFlow == null) {
                        val startAction = onStartYtmusicOAuth ?: authController::startYtmusicTvOAuthLogin
                        Button(onClick = startAction, enabled = !authBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("使用 Google 登录（TV）")
                        }
                        onImportYtmusicOAuthFile?.let { action ->
                            OutlinedButton(onClick = action, enabled = !authBusy, modifier = Modifier.fillMaxWidth()) {
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
                            shape = MaterialTheme.shapes.extraLarge,
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
                        Text(
                            verificationUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = authController::cancelYtmusicTvOAuthLogin) { Text("取消授权") }
                    }
                }
            }
        }
    }

    if (provider.providerId in EXPRESSIVE_PLAYBACK_REPORTING_PROVIDER_IDS) {
        ExpressiveSettingsGroup(title = "播放数据") {
            ExpressiveToggleRow(
                title = "播放数据上报",
                supportingText = "默认关闭。开启后仅在已登录时同步实际播放行为，用于${provider.providerName}的播放历史与推荐优化。",
                checked = provider.providerId in settings.settings.playbackReportingProviderIds,
                enabled = !busy,
                onCheckedChange = { value ->
                    settingsController.setProviderPlaybackReportingEnabled(provider.providerId, value)
                },
            )
        }
    }

    if (auth.isLoggedIn && credentialBackupActions.exportProvider != null) {
        ExpressiveSettingsGroup(title = "登录凭证") {
            ExpressiveSettingsRow(
                title = "导出当前音源凭证",
                supportingText = "用于在其他设备恢复 ${provider.providerName} 登录状态",
                enabled = !authBusy,
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

    openSupportedLinksSettings?.let { action ->
        ExpressiveSettingsGroup(title = "系统集成") {
            ExpressiveSettingsRow(
                title = "链接打开方式",
                supportingText = "在系统设置中选择是否允许 FuoEvolve 打开 ${provider.providerName} 相关链接",
                leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = action,
            )
        }
    }

    authController.authError(provider.providerId)?.let { error ->
        ExpressiveSettingsGroup(title = "错误") {
            ExpressiveSettingsRow(title = error, titleColor = MaterialTheme.colorScheme.error)
        }
    }
    authUiState.feedback?.let { feedback ->
        ExpressiveSettingsGroup(title = "状态") { ExpressiveSettingsRow(title = feedback) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveProviderDisplaySettings(
    state: ProviderCatalogUiState,
    controller: ProviderCatalogFeatureController,
    provider: ProviderInfo,
    enabled: Boolean,
) {
    val sections = listOf(
        ProviderDisplaySection.Search to "搜索",
        ProviderDisplaySection.Recommend to "推荐",
        ProviderDisplaySection.Explore to "探索",
        ProviderDisplaySection.Mine to "我的",
        ProviderDisplaySection.Replace to "智能替换",
    )
    ExpressiveSettingsGroup(title = "显示与参与范围") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
        ) {
            Text(
                "选择此音源参与的页面和功能。按钮会使用 Material Expressive 的状态形变反馈。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                sections.forEach { (section, label) ->
                    val selected = expressiveProviderShownIn(state, provider.providerId, section)
                    TonalToggleButton(
                        checked = selected,
                        onCheckedChange = {
                            controller.setDisplayProviderEnabled(section, provider.providerId, it)
                        },
                        enabled = enabled,
                    ) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(FuoSpacing.xs))
                        Text(label)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TonalToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        ) {
            Text(if (checked) "已开启" else "已关闭")
        }
    }
}

@Composable
private fun ExpressiveSettingsGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
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
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun ExpressiveSettingsRow(
    title: String,
    supportingText: String? = null,
    enabled: Boolean = true,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick ?: {},
        enabled = enabled && onClick != null,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FuoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingContent?.invoke()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor.copy(alpha = if (enabled) 1f else 0.55f),
                )
                supportingText?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

internal fun providerDropTargetIndex(
    startIndex: Int,
    dragDistancePx: Float,
    itemExtentPx: Float,
    lastIndex: Int,
): Int {
    if (lastIndex < 0) return -1
    if (startIndex !in 0..lastIndex || itemExtentPx <= 0f) return startIndex.coerceIn(0, lastIndex)
    val indexDelta = (dragDistancePx / itemExtentPx).roundToInt()
    return (startIndex + indexDelta).coerceIn(0, lastIndex)
}

private fun expressiveProviderShownIn(
    state: ProviderCatalogUiState,
    providerId: String,
    section: ProviderDisplaySection,
): Boolean = providerId in when (section) {
    ProviderDisplaySection.Search -> state.searchProviderIds
    ProviderDisplaySection.Recommend -> state.recommendProviderIds
    ProviderDisplaySection.Explore -> state.exploreProviderIds
    ProviderDisplaySection.Mine -> state.mineProviderIds
    ProviderDisplaySection.Replace -> state.replacementProviderIds
}

private fun expressiveProviderDisplaySummary(state: ProviderCatalogUiState, providerId: String): String =
    listOf(
        ProviderDisplaySection.Search to "搜索",
        ProviderDisplaySection.Recommend to "推荐",
        ProviderDisplaySection.Explore to "探索",
        ProviderDisplaySection.Mine to "我的",
    ).filter { (section, _) -> expressiveProviderShownIn(state, providerId, section) }
        .joinToString(" · ") { it.second }
        .ifBlank { "未参与页面展示" }

private fun expressiveProviderStatusText(enabled: Boolean, auth: ProviderAuthState?): String {
    val enabledText = if (enabled) "已启用" else "未启用"
    val loginText = auth?.takeIf { it.isLoggedIn }?.let { state ->
        state.userName?.takeIf { it.isNotBlank() }?.let { "已登录 · $it" } ?: "已登录"
    } ?: "未登录"
    return "$enabledText · $loginText"
}

private fun expressiveProviderLoginModeLabel(mode: ProviderLoginMode): String = when (mode) {
    ProviderLoginMode.WebView -> "网页"
    ProviderLoginMode.Cookie -> "Cookie"
    ProviderLoginMode.Headers -> "Headers"
    ProviderLoginMode.OAuth -> "OAuth"
}
