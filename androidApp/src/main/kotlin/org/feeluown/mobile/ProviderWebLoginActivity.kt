package org.feeluown.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        statusView = TextView(this).apply {
            text = "完成登录后会自动获取 Cookie"
            setPadding(24, 12, 24, 12)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
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
            setPadding(24, 0, 12, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val doneButton = Button(this).apply {
            text = "完成"
            setOnClickListener { finishWithCookies(webView.url, auto = false) }
        }
        val closeButton = Button(this).apply {
            text = "关闭"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18, 12, 12)
            addView(titleView)
            addView(doneButton)
            addView(closeButton)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        webView.loadUrl(loginUrl, mapOf("User-Agent" to DESKTOP_USER_AGENT))
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
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
