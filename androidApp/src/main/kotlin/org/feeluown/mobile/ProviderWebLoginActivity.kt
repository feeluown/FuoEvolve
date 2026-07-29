package org.feeluown.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class ProviderWebLoginActivity : ComponentActivity() {
    private lateinit var providerId: String
    private lateinit var providerName: String
    private lateinit var loginUrl: String
    private lateinit var cookieKeyGroups: List<List<String>>
    private lateinit var webView: WebView
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
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager().flush()
                    finishWithCookies(url, auto = true)
                }
            }
        }

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
        if (!auto && ::webView.isInitialized) {
            webView.stopLoading()
        }
        cookieManager().flush()
        val cookies = collectCookies(currentUrl)
        if (auto && !hasRequiredCookies(cookies)) return
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

    private fun collectCookies(currentUrl: String?): Map<String, String> {
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
        statusMessage = if (result.isEmpty()) {
            "等待登录 Cookie"
        } else {
            "已获取 ${result.size} 个 Cookie"
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

        private const val EXTRA_PROVIDER_NAME = "provider_name"
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_COOKIE_KEY_GROUPS = "cookie_key_groups"
        private const val PREFS_NAME = "fuo_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR_SCHEME = "theme_color_scheme"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun createIntent(context: Context, provider: ProviderInfo): Intent {
            val loginConfig = requireNotNull(provider.loginConfig)
            val keyGroups = JSONArray()
            loginConfig.cookieKeyGroups.forEach { group ->
                val groupArray = JSONArray()
                group.forEach { groupArray.put(it) }
                keyGroups.put(groupArray)
            }
            return Intent(context, ProviderWebLoginActivity::class.java)
                .putExtra(EXTRA_PROVIDER_ID, provider.providerId)
                .putExtra(EXTRA_PROVIDER_NAME, provider.providerName)
                .putExtra(EXTRA_LOGIN_URL, loginConfig.loginUrl)
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
