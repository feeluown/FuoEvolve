package org.feeluown.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

class ProviderWebLoginActivity : Activity() {
    private lateinit var providerId: String
    private lateinit var providerName: String
    private lateinit var loginUrl: String
    private lateinit var cookieKeyGroups: List<List<String>>
    private lateinit var statusView: TextView
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
        providerName = intent.getStringExtra(EXTRA_PROVIDER_NAME).orEmpty().ifBlank { providerId }
        loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        cookieKeyGroups = parseCookieKeyGroups(intent.getStringExtra(EXTRA_COOKIE_KEY_GROUPS).orEmpty())
        if (providerId.isBlank() || loginUrl.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        cookieManager().setAcceptCookie(true)
        val userAgent = loginUserAgent()
        val useMobileViewport = providerId == "bilibili"
        val colors = webLoginColors()

        statusView = TextView(this).apply {
            text = "完成登录后会自动获取 Cookie"
            textSize = 13f
            setTextColor(colors.onSurfaceVariant)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setBackgroundColor(colors.surfaceVariant)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
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

        val titleView = TextView(this).apply {
            text = "$providerName 浏览器登录"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.onSurface)
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeButton = toolbarButton(text = "关闭", filled = false, colors = colors) {
            setResult(RESULT_CANCELED)
            finish()
        }
        val doneButton = toolbarButton(text = "完成", filled = true, colors = colors) {
            finishWithCookies(webView.url, auto = false)
        }
        val toolbarDivider = View(this).apply {
            setBackgroundColor(colors.outline)
        }
        val toolbarContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(16), dp(8))
            minimumHeight = dp(56)
            setBackgroundColor(colors.surface)
            addView(titleView)
            addView(closeButton)
            addView(doneButton)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.surface)
            addView(toolbarContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(toolbarDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        webView.loadUrl(loginUrl, mapOf("User-Agent" to userAgent))
    }

    private fun loginUserAgent(): String {
        return if (providerId == "bilibili") MOBILE_USER_AGENT else DESKTOP_USER_AGENT
    }

    private fun toolbarButton(
        text: String,
        filled: Boolean,
        colors: WebLoginColors,
        onClick: () -> Unit,
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minWidth = dp(64)
            minHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(if (filled) colors.onPrimary else colors.onSurfaceVariant)
            background = roundedBackground(
                color = if (filled) colors.primary else Color.TRANSPARENT,
                strokeColor = if (filled) Color.TRANSPARENT else colors.outline,
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun webLoginColors(): WebLoginColors {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = enumValue(
            preferences.getString(KEY_THEME_MODE, null),
            ThemeMode.System,
        )
        val scheme = enumValue(
            preferences.getString(KEY_THEME_COLOR_SCHEME, null),
            ThemeColorScheme.Dynamic,
        )
        val darkTheme = resolvedDarkTheme(themeMode)
        if (scheme == ThemeColorScheme.Dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return dynamicWebLoginColors(darkTheme)
        }
        val primary = presetPrimaryColor(
            if (scheme == ThemeColorScheme.Dynamic) ThemeColorScheme.ExpressiveDefault else scheme,
            darkTheme,
        )
        return WebLoginColors(
            background = if (darkTheme) Color.parseColor("#111411") else Color.WHITE,
            surface = if (darkTheme) Color.parseColor("#191C19") else Color.WHITE,
            surfaceVariant = if (darkTheme) Color.parseColor("#424940") else Color.parseColor("#EDF2EC"),
            outline = if (darkTheme) Color.parseColor("#8C9389") else Color.parseColor("#D6DED5"),
            primary = primary,
            onPrimary = if (darkTheme) Color.parseColor("#1C1B1F") else Color.WHITE,
            onSurface = if (darkTheme) Color.parseColor("#E3E3DE") else Color.parseColor("#1B1D1A"),
            onSurfaceVariant = if (darkTheme) Color.parseColor("#C3C8BF") else Color.parseColor("#555F55"),
        )
    }

    private fun dynamicWebLoginColors(darkTheme: Boolean): WebLoginColors {
        val primary = if (darkTheme) {
            getColor(android.R.color.system_accent1_200)
        } else {
            getColor(android.R.color.system_accent1_600)
        }
        return WebLoginColors(
            background = if (darkTheme) Color.parseColor("#111411") else Color.WHITE,
            surface = if (darkTheme) Color.parseColor("#191C19") else Color.WHITE,
            surfaceVariant = if (darkTheme) Color.parseColor("#424940") else Color.parseColor("#EDF2EC"),
            outline = if (darkTheme) Color.parseColor("#8C9389") else Color.parseColor("#D6DED5"),
            primary = primary,
            onPrimary = if (darkTheme) Color.parseColor("#1C1B1F") else Color.WHITE,
            onSurface = if (darkTheme) Color.parseColor("#E3E3DE") else Color.parseColor("#1B1D1A"),
            onSurfaceVariant = if (darkTheme) Color.parseColor("#C3C8BF") else Color.parseColor("#555F55"),
        )
    }

    private fun presetPrimaryColor(scheme: ThemeColorScheme, darkTheme: Boolean): Int {
        val raw = when (scheme) {
            ThemeColorScheme.Dynamic,
            ThemeColorScheme.ExpressiveDefault -> if (darkTheme) "#D0BCFF" else "#6750A4"
            ThemeColorScheme.FuoGreen -> if (darkTheme) "#93DDAA" else "#246B43"
            ThemeColorScheme.OceanBlue -> if (darkTheme) "#A7C8FF" else "#0066B3"
            ThemeColorScheme.Violet -> if (darkTheme) "#D7B8FF" else "#7650B4"
            ThemeColorScheme.Rose -> if (darkTheme) "#FFB1C8" else "#B13F66"
            ThemeColorScheme.Amber -> if (darkTheme) "#FFCF73" else "#8A5A00"
        }
        return Color.parseColor(raw)
    }

    private fun resolvedDarkTheme(themeMode: ThemeMode): Boolean {
        return when (themeMode) {
            ThemeMode.System -> {
                val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                uiMode == Configuration.UI_MODE_NIGHT_YES
            }
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
        return runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
    }

    private fun roundedBackground(color: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        statusView.text = if (result.isEmpty()) {
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

    private data class WebLoginColors(
        val background: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val outline: Int,
        val primary: Int,
        val onPrimary: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
    )
}
