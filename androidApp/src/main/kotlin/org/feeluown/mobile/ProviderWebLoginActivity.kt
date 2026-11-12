package org.feeluown.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class ProviderWebLoginActivity : ComponentActivity() {
    private lateinit var providerId: String
    private lateinit var providerName: String
    private lateinit var loginUrl: String
    private lateinit var cookieKeyGroups: List<List<String>>
    private lateinit var webView: WebView

    /**
     * Values of the required auth cookies as they existed before the login page was opened.
     *
     * An expired provider session does not remove cookies from the persistent WebView jar: the jar
     * still contains every required cookie NAME with a dead VALUE. Auto-finishing purely on
     * "required cookies exist" therefore captured the same dead session again and re-stored it,
     * which is what made 退出登录 the only workaround users had. Comparing against this baseline
     * means the login page only completes on its own after the browser session actually changed.
     */
    private var baselineAuthCookies: Map<String, String> = emptyMap()

    /**
     * Claimed by whichever callback first sees the provider's OAuth redirect, before the activity
     * result is produced. It takes precedence over the cookie result because a cookie-only session
     * cannot be renewed later, and it is an atomic claim because [WebViewClient.shouldInterceptRequest]
     * runs on a WebView worker thread while the cookie callbacks run on the main thread.
     */
    private val authorizationCodeCaptured = AtomicBoolean(false)
    private var statusMessage by mutableStateOf("")

    @SuppressLint("SetJavaScriptEnabled") // 提供方登录页完成认证需要 JavaScript。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        providerName = intent.getStringExtra(EXTRA_PROVIDER_NAME).orEmpty().ifBlank { providerId }
        loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        cookieKeyGroups = parseCookieKeyGroups(intent.getStringExtra(EXTRA_COOKIE_KEY_GROUPS).orEmpty())
        if (providerId.isBlank() || loginUrl.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        statusMessage = getString(R.string.provider_login_cookie_hint)

        cookieManager().setAcceptCookie(true)
        val userAgent = loginUserAgent()
        val useMobileViewport = providerId == "bilibili"
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = userAgent
            settings.useWideViewPort = !useMobileViewport
            settings.loadWithOverviewMode = !useMobileViewport
            cookieManager().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // minSdk is 24, so 24+ always consults the WebResourceRequest overload.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    return handleAuthorizationCodeRedirect(request.url?.toString())
                }

                /**
                 * Fallback for the redirect that [shouldOverrideUrlLoading] does not report.
                 *
                 * QQ's OAuth hop back to `y.qq.com/portal/wx_redirect.html` is an HTTP 3xx redirect,
                 * and WebView does not reliably consult [shouldOverrideUrlLoading] for those, so the
                 * single-use code would otherwise be consumed by the page we never saw. This callback
                 * fires for the target of every request, redirects included.
                 *
                 * It runs on a WebView worker thread for every subresource, so the main-frame guard and
                 * the cheap substring prefilter come first, and the completion is bounced to the main
                 * thread (activity result + finish must run there). The code is never logged.
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (authorizationCodeCaptured.get()) return null
                    if (isFinishing || isDestroyed) return null
                    if (!request.isForMainFrame) return null
                    val url = request.url?.toString() ?: return null
                    if (!mayBeAuthorizationCodeRedirect(url)) return null
                    val redirect = authorizationCodeFromRedirect(url) ?: return null
                    // Claim from this worker thread before anything else can complete with cookies.
                    if (!authorizationCodeCaptured.compareAndSet(false, true)) return null
                    runOnUiThread { completeWithAuthorizationCode(redirect) }
                    // Serve an empty body so the redirect page never runs and cannot consume the code.
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // The redirect can arrive as the last navigation of the login page; never let the
                    // cookie fallback overwrite an already captured authorization code.
                    if (authorizationCodeCaptured.get()) return
                    cookieManager().flush()
                    finishWithCookies(url, auto = true)
                }
            }
        }

        // Snapshot the browser session before the login page can touch it. See [baselineAuthCookies].
        baselineAuthCookies = authCookieSnapshot()

        setContent {
            FuoTheme(
                themeMode = webLoginThemeMode(),
                themeColorScheme = webLoginThemeColorScheme(),
            ) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(getString(R.string.provider_browser_login_title, providerName)) },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.close))
                                }
                            },
                            actions = {
                                TextButton(onClick = { finishWithCookies(webView.url, auto = false) }) {
                                    Text(getString(R.string.done))
                                }
                            },
                        )
                    },
                ) { paddingValues ->
                    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = statusMessage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        AndroidView(
                            factory = { webView },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
        configureSystemBars(webLoginThemeMode())
        webView.loadUrl(loginUrl, mapOf("User-Agent" to userAgent))
    }

    private fun loginUserAgent(): String {
        return if (providerId == "bilibili") MOBILE_USER_AGENT else DESKTOP_USER_AGENT
    }

    private fun webLoginThemeMode(): ThemeMode {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return enumValue(
            preferences.getString(KEY_THEME_MODE, null),
            ThemeMode.System,
        )
    }

    private fun webLoginThemeColorScheme(): ThemeColorScheme {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return enumValue(
            preferences.getString(KEY_THEME_COLOR_SCHEME, null),
            ThemeColorScheme.Dynamic,
        )
    }

    private fun configureSystemBars(themeMode: ThemeMode) {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val darkTheme = when (themeMode) {
            ThemeMode.System -> systemDark
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
        return runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
    }

    override fun onDestroy() {
        cookieManager().flush()
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun finishWithCookies(currentUrl: String?, auto: Boolean) {
        if (authorizationCodeCaptured.get()) return
        if (!auto && ::webView.isInitialized) {
            webView.stopLoading()
        }
        cookieManager().flush()
        val cookies = collectCookies(currentUrl)
        if (auto && !hasRequiredCookies(cookies)) return
        if (auto && !hasFreshAuthCookies(cookies)) {
            // The jar still holds the cookies the app already had, i.e. the page was opened with a
            // known-rejected session and the user has not signed in again yet. Keep waiting (or let
            // the user press 完成 explicitly) instead of re-storing that dead session.
            statusMessage = STALE_SESSION_HINT
            return
        }
        if (!auto && cookies.isEmpty()) {
            Toast.makeText(this, "未获取到 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        cookieManager().flush()
        val data = Intent()
            .putExtra(EXTRA_PROVIDER_ID, providerId)
            .putExtra(EXTRA_COOKIES_JSON, cookiesToJson(cookies))
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * QQ Music's authorization redirect: [OAUTH_REDIRECT_HOST] + [OAUTH_REDIRECT_PATH] + a `code`.
     *
     * The single-use [code] is consumed the moment that page is allowed to run (its JavaScript posts
     * it back to QQ), so returning `true` from [WebViewClient.shouldOverrideUrlLoading] — i.e. never
     * loading the URL — is what keeps the code exchangeable by the provider. Cookies are deliberately
     * not collected in this case: the exchanged credential synthesizes its own cookies and is the only
     * renewable session we can persist.
     */
    private fun handleAuthorizationCodeRedirect(url: String?): Boolean {
        if (authorizationCodeCaptured.get()) return true
        val redirect = authorizationCodeFromRedirect(url) ?: return false
        if (!authorizationCodeCaptured.compareAndSet(false, true)) return true
        completeWithAuthorizationCode(redirect)
        return true
    }

    /**
     * Cheap substring prefilter for [shouldInterceptRequest], which is called for every request of the
     * page on a worker thread: full URI parsing only happens for URLs that can possibly match.
     */
    private fun mayBeAuthorizationCodeRedirect(url: String): Boolean =
        url.contains(OAUTH_REDIRECT_PATH, ignoreCase = true) &&
            url.contains(OAUTH_REDIRECT_HOST, ignoreCase = true)

    /**
     * Single parser for the provider OAuth redirect: the single-use [AuthorizationCodeRedirect.code]
     * plus the account ecosystem the code belongs to.
     *
     * QQ Music sends both QQ-account (login_type=1) and WeChat-account (login_type=2) logins back to
     * the same redirect and the exchange payload differs, so the type has to travel with the code.
     */
    private fun authorizationCodeFromRedirect(url: String?): AuthorizationCodeRedirect? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        // Non-hierarchical navigations (about:blank, intent:…) have no host and must not match.
        val host = uri.host ?: return null
        if (!host.equals(OAUTH_REDIRECT_HOST, ignoreCase = true)) return null
        if (uri.path != OAUTH_REDIRECT_PATH) return null
        val code = uri.getQueryParameter(OAUTH_REDIRECT_CODE_PARAMETER)?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val loginType = uri.getQueryParameter(OAUTH_REDIRECT_LOGIN_TYPE_PARAMETER)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        return AuthorizationCodeRedirect(code = code, loginType = loginType)
    }

    /** A captured provider OAuth redirect: the single-use code and its account ecosystem. */
    private data class AuthorizationCodeRedirect(val code: String, val loginType: Int?)

    /**
     * Produces the activity result for a code whose [authorizationCodeCaptured] claim the caller won.
     * Must run on the main thread. Never logs the code: it is a single-use credential.
     */
    private fun completeWithAuthorizationCode(redirect: AuthorizationCodeRedirect) {
        if (isFinishing || isDestroyed) return
        if (::webView.isInitialized) {
            webView.stopLoading()
        }
        val data = Intent()
            .putExtra(EXTRA_PROVIDER_ID, providerId)
            .putExtra(EXTRA_AUTHORIZATION_CODE, redirect.code)
        // Only present when the redirect said which ecosystem the code belongs to; absent means the
        // provider default (QQ account).
        redirect.loginType?.let { loginType -> data.putExtra(EXTRA_AUTHORIZATION_LOGIN_TYPE, loginType) }
        setResult(RESULT_OK, data)
        finish()
    }

    /** Required auth cookie values currently in the jar, keyed by cookie name. */
    private fun authCookieSnapshot(): Map<String, String> {
        val requiredKeys = cookieKeyGroups.flatten().toSet()
        if (requiredKeys.isEmpty()) return emptyMap()
        return collectCookies(currentUrl = null, updateStatus = false).filterKeys { it in requiredKeys }
    }

    /**
     * True when at least one cookie of the satisfied group changed since the login page was opened,
     * i.e. the user actually completed a new sign-in instead of the page re-reading a dead session.
     */
    private fun hasFreshAuthCookies(cookies: Map<String, String>): Boolean {
        if (cookieKeyGroups.isEmpty()) return true
        return cookieKeyGroups.any { group ->
            group.isNotEmpty() &&
                group.all { key -> cookies[key].isNullOrBlank().not() } &&
                group.any { key -> cookies[key] != baselineAuthCookies[key] }
        }
    }

    private fun collectCookies(currentUrl: String?, updateStatus: Boolean = true): Map<String, String> {
        val result = linkedMapOf<String, String>()
        cookieLookupUrls(currentUrl).forEach { url ->
            cookieManager().getCookie(url)
                ?.split(";")
                ?.forEach { part ->
                    val pieces = part.split("=", limit = 2)
                    if (pieces.size == 2) {
                        val key = pieces[0].trim()
                        val value = pieces[1].trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            result[key] = value
                        }
                    }
                }
        }
        if (updateStatus) {
            statusMessage = if (result.isEmpty()) {
                "等待登录 Cookie"
            } else {
                "已获取 ${result.size} 个 Cookie"
            }
        }
        return result
    }

    private fun cookieLookupUrls(currentUrl: String?): List<String> {
        val urls = linkedSetOf<String>()
        listOf(loginUrl, currentUrl, webView.url, webView.originalUrl)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .forEach { urls.add(it) }
        providerCookieHosts(providerId).forEach { host ->
            urls.add("https://$host")
            urls.add("http://$host")
        }
        return urls.toList()
    }

    private fun providerCookieHosts(providerId: String): List<String> {
        return when (providerId) {
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
            "ytmusic" -> listOf(
                "music.youtube.com",
                "youtube.com",
                "www.youtube.com",
                "accounts.google.com",
                "google.com",
            )
            else -> emptyList()
        }
    }

    private fun cookieManager(): CookieManager = CookieManager.getInstance()

    private fun hasRequiredCookies(cookies: Map<String, String>): Boolean {
        if (cookieKeyGroups.isEmpty()) return cookies.isNotEmpty()
        return cookieKeyGroups.any { group ->
            group.all { key -> cookies[key].isNullOrBlank().not() }
        }
    }

    private fun cookiesToJson(cookies: Map<String, String>): String {
        val json = JSONObject()
        cookies.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    private fun parseCookieKeyGroups(raw: String): List<List<String>> {
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val group = array.getJSONArray(index)
            List(group.length()) { keyIndex -> group.getString(keyIndex) }
        }
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_COOKIES_JSON = "cookies_json"

        /** OAuth authorization code captured from a provider login redirect, when one was seen. */
        const val EXTRA_AUTHORIZATION_CODE = "authorization_code"

        /**
         * Account ecosystem of [EXTRA_AUTHORIZATION_CODE], taken from the redirect's `login_type`
         * (1 = QQ account, 2 = WeChat account). Absent means the provider default.
         */
        const val EXTRA_AUTHORIZATION_LOGIN_TYPE = "authorization_login_type"

        private const val OAUTH_REDIRECT_HOST = "y.qq.com"
        private const val OAUTH_REDIRECT_PATH = "/portal/wx_redirect.html"
        private const val OAUTH_REDIRECT_CODE_PARAMETER = "code"
        private const val OAUTH_REDIRECT_LOGIN_TYPE_PARAMETER = "login_type"

        private const val EXTRA_PROVIDER_NAME = "provider_name"
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_COOKIE_KEY_GROUPS = "cookie_key_groups"
        private const val PREFS_NAME = "fuo_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR_SCHEME = "theme_color_scheme"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val STALE_SESSION_HINT =
            "当前网页登录会话可能已失效，请在本页面重新登录；完成后可点按右上角「完成」。"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * @param loginUrl the selected [ProviderLoginChoice.loginUrl] when the provider offers more
         *   than one account ecosystem, or null/blank to use the provider's default login URL.
         */
        fun createIntent(context: Context, provider: ProviderInfo, loginUrl: String? = null): Intent {
            val loginConfig = requireNotNull(provider.loginConfig)
            val effectiveLoginUrl = loginUrl?.takeIf(String::isNotBlank) ?: loginConfig.loginUrl
            val keyGroups = JSONArray()
            loginConfig.cookieKeyGroups.forEach { group ->
                val groupArray = JSONArray()
                group.forEach { groupArray.put(it) }
                keyGroups.put(groupArray)
            }
            return Intent(context, ProviderWebLoginActivity::class.java)
                .putExtra(EXTRA_PROVIDER_ID, provider.providerId)
                .putExtra(EXTRA_PROVIDER_NAME, provider.providerName)
                .putExtra(EXTRA_LOGIN_URL, effectiveLoginUrl)
                .putExtra(EXTRA_COOKIE_KEY_GROUPS, keyGroups.toString())
        }

        fun clearWebLoginState() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }

}
