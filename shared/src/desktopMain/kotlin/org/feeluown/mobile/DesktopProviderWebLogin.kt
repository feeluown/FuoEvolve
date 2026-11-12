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
import dev.nucleusframework.webview.request.RequestInterceptor
import dev.nucleusframework.webview.request.WebRequest
import dev.nucleusframework.webview.request.WebRequestInterceptResult
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.WebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface DesktopWebLoginResult {
    data class Success(val cookiesJson: String) : DesktopWebLoginResult

    /**
     * The provider's OAuth redirect was intercepted and its navigation cancelled before the page ran,
     * so the single-use [code] is still exchangeable by the provider.
     */
    data class AuthorizationCode(val code: String) : DesktopWebLoginResult

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
    // An OAuth authorize page can only end in the redirect that carries the single-use authorization
    // code. For such a flow the cookie poll must never complete the login first: a cookie-only session
    // is not renewable, and the (incognito) jar may already satisfy the required names mid-flow.
    val requiresAuthorizationCode = requiresDesktopAuthorizationCode(config.loginUrl)
    val currentOnResult by rememberUpdatedState(onResult)
    var finished by remember(provider.providerId) { mutableStateOf(false) }

    fun finish(result: DesktopWebLoginResult) {
        if (!finished) {
            finished = true
            currentOnResult(result)
        }
    }

    // The navigation interceptor runs on the native web view callback, not necessarily on the
    // composition thread, so the captured code is handed over through an atomic slot and consumed by
    // the polling effect below instead of being written into compose state from that callback.
    val capturedAuthorizationCode = remember(provider.providerId) { AtomicReference<String?>(null) }
    val navigationInterceptor = remember(provider.providerId, capturedAuthorizationCode) {
        object : RequestInterceptor {
            override fun onInterceptUrlRequest(
                request: WebRequest,
                navigator: WebViewNavigator,
            ): WebRequestInterceptResult {
                val code = desktopAuthorizationCodeFromRedirect(request.url)
                    ?: return WebRequestInterceptResult.Allow
                capturedAuthorizationCode.compareAndSet(null, code)
                // Cancel the navigation: the redirect page would otherwise consume the single-use code.
                return WebRequestInterceptResult.Reject
            }
        }
    }
    val navigator = rememberWebViewNavigator(requestInterceptor = navigationInterceptor)

    LaunchedEffect(provider.providerId, state) {
        while (isActive && !finished) {
            capturedAuthorizationCode.get()?.let { code ->
                finish(DesktopWebLoginResult.AuthorizationCode(code))
                return@LaunchedEffect
            }
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
            if (!requiresAuthorizationCode && hasRequiredDesktopWebLoginCookies(cookies, config.cookieKeyGroups)) {
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

/**
 * True when [loginUrl] is a provider OAuth authorize page, i.e. the flow must end in the redirect that
 * carries the authorization code. Cookies are only a fallback for providers whose login URL is an
 * ordinary page; completing an OAuth flow from cookies would store a session that cannot be renewed.
 */
internal fun requiresDesktopAuthorizationCode(loginUrl: String): Boolean =
    loginUrl.contains(OAUTH_AUTHORIZE_PATH_MARKER, ignoreCase = true)

/**
 * QQ Music's authorization redirect, or null when [url] is not that redirect.
 *
 * The redirect carries the single-use authorization code that the provider exchanges for a renewable
 * session; the code itself must never be logged.
 */
internal fun desktopAuthorizationCodeFromRedirect(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (!host.equals(OAUTH_REDIRECT_HOST, ignoreCase = true)) return null
    val path = uri.path ?: return null
    if (path != OAUTH_REDIRECT_PATH) return null
    val rawQuery = uri.rawQuery ?: return null
    return rawQuery.split('&').firstNotNullOfOrNull { parameter ->
        val separator = parameter.indexOf('=')
        if (separator <= 0) return@firstNotNullOfOrNull null
        if (parameter.substring(0, separator) != OAUTH_REDIRECT_CODE_PARAMETER) return@firstNotNullOfOrNull null
        runCatching { URLDecoder.decode(parameter.substring(separator + 1), "UTF-8") }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
}

private const val OAUTH_AUTHORIZE_PATH_MARKER = "oauth2.0/authorize"
private const val OAUTH_REDIRECT_HOST = "y.qq.com"
private const val OAUTH_REDIRECT_PATH = "/portal/wx_redirect.html"
private const val OAUTH_REDIRECT_CODE_PARAMETER = "code"
private const val COOKIE_POLL_INTERVAL_MILLIS = 500L
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
