package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface DesktopWebLoginResult {
    data class Success(val cookiesJson: String) : DesktopWebLoginResult
    data object Cancelled : DesktopWebLoginResult
    data class Failure(val message: String) : DesktopWebLoginResult
}

@Composable
internal fun DesktopProviderWebLogin(
    provider: ProviderInfo,
    onResult: (DesktopWebLoginResult) -> Unit,
) {
    val config = requireNotNull(provider.loginConfig) {
        "${provider.providerName} 未配置网页登录地址"
    }
    require(config.loginUrl.isNotBlank()) {
        "${provider.providerName} 未配置有效网页登录地址"
    }
    require(config.cookieKeyGroups.isNotEmpty()) {
        "${provider.providerName} 未配置登录 Cookie 判定规则"
    }

    val state = rememberWebViewState(config.loginUrl) {
        customUserAgentString = desktopWebLoginUserAgent(provider.providerId)
        isJavaScriptEnabled = true
        desktopWebSettings.incognito = true
        desktopWebSettings.enableClipboard = true
    }
    val navigator = rememberWebViewNavigator()
    val currentOnResult by rememberUpdatedState(onResult)
    var finished by remember(provider.providerId) { mutableStateOf(false) }

    fun finish(result: DesktopWebLoginResult) {
        if (!finished) {
            finished = true
            currentOnResult(result)
        }
    }

    LaunchedEffect(provider.providerId, state) {
        while (isActive && !finished) {
            val cookieUrls = desktopWebLoginCookieUrls(
                providerId = provider.providerId,
                loginUrl = config.loginUrl,
                currentUrl = state.lastLoadedUrl,
            )
            val cookies = mutableMapOf<String, String>()
            for (url in cookieUrls) {
                val urlCookies = runCatching { state.cookieManager.getCookies(url) }
                    .getOrDefault(emptyList())
                urlCookies
                    .filter { it.name.isNotBlank() && it.value.isNotBlank() }
                    .forEach { cookie -> cookies[cookie.name] = cookie.value }
            }
            if (hasRequiredDesktopWebLoginCookies(cookies, config.cookieKeyGroups)) {
                finish(DesktopWebLoginResult.Success(Json.encodeToString(cookies)))
                return@LaunchedEffect
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { navigator.navigateBack() },
                    enabled = navigator.canGoBack,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                IconButton(onClick = navigator::reload) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
                Text(
                    text = "登录 ${provider.providerName}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { finish(DesktopWebLoginResult.Cancelled) }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize().weight(1f)) {
                WebView(
                    state = state,
                    navigator = navigator,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.isLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

internal fun desktopWebLoginUserAgent(providerId: String): String =
    if (providerId == "bilibili") MOBILE_USER_AGENT else DESKTOP_USER_AGENT

internal fun desktopWebLoginCookieUrls(
    providerId: String,
    loginUrl: String,
    currentUrl: String?,
): List<String> {
    val urls = linkedSetOf<String>()
    listOf(loginUrl, currentUrl)
        .filterNotNull()
        .filter(String::isNotBlank)
        .forEach(urls::add)
    desktopWebLoginCookieHosts(providerId).forEach { host ->
        urls += "https://$host"
        urls += "http://$host"
    }
    return urls.toList()
}

internal fun hasRequiredDesktopWebLoginCookies(
    cookies: Map<String, String>,
    cookieKeyGroups: List<List<String>>,
): Boolean = cookieKeyGroups.any { group ->
    group.isNotEmpty() && group.all { key -> cookies[key]?.isNotBlank() == true }
}

private fun desktopWebLoginCookieHosts(providerId: String): List<String> = when (providerId) {
    "netease" -> listOf(
        "music.163.com",
        "m.music.163.com",
        "interface.music.163.com",
        "interface3.music.163.com",
    )
    "qqmusic" -> listOf(
        "y.qq.com",
        "u.y.qq.com",
        "i.y.qq.com",
        "c.y.qq.com",
        "graph.qq.com",
        "ptlogin2.qq.com",
        "qq.com",
    )
    "bilibili" -> listOf(
        "www.bilibili.com",
        "api.bilibili.com",
        "passport.bilibili.com",
        "bilibili.com",
    )
    else -> emptyList()
}

private const val COOKIE_POLL_INTERVAL_MILLIS = 500L
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
