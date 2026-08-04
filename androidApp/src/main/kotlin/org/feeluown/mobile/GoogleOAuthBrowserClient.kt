package org.feeluown.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class GoogleOAuthBrowserToken(
    val accessToken: String,
    val expiresAtMillis: Long?,
    val grantedScopes: Set<String>,
)

/** Google OAuth Authorization Code + PKCE flow backed by the system browser or a Custom Tab. */
class GoogleOAuthBrowserClient(
    private val context: Context,
    private val clientId: String = BuildConfig.GOOGLE_OAUTH_BROWSER_CLIENT_ID,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun startAuthorization(scopes: List<String>) {
        require(clientId.isNotBlank()) {
            "未配置浏览器 OAuth client ID，请设置 FUO_GOOGLE_OAUTH_BROWSER_CLIENT_ID"
        }
        val normalizedScopes = scopes.map(String::trim).filter(String::isNotBlank).distinct()
        require(normalizedScopes.isNotEmpty()) { "未配置 Google OAuth scope" }

        val verifier = randomUrlSafeValue()
        val state = randomUrlSafeValue()
        savePendingAuthorization(
            state = state,
            verifier = verifier,
            scopes = normalizedScopes,
        )
        val authorizationUri = Uri.parse(AUTHORIZATION_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI.toString())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", normalizedScopes.joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        openInBrowser(authorizationUri)
    }

    fun isRedirectUri(uri: Uri?): Boolean = uri?.let {
        it.scheme == REDIRECT_URI.scheme &&
            it.host == REDIRECT_URI.host &&
            it.path == REDIRECT_URI.path
    } == true

    suspend fun handleRedirect(uri: Uri): GoogleOAuthBrowserToken {
        require(isRedirectUri(uri)) { "Google OAuth 回调地址无效" }
        val pending = readPendingAuthorization()
            ?: error("Google OAuth 登录状态已过期，请重新发起登录")
        val returnedState = uri.getQueryParameter("state")
        check(returnedState == pending.state) { "Google OAuth state 校验失败" }
        clearPendingAuthorization()

        uri.getQueryParameter("error")?.let { errorCode ->
            val description = uri.getQueryParameter("error_description").orEmpty()
            error("$errorCode${description.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
        val code = uri.getQueryParameter("code").orEmpty()
        check(code.isNotBlank()) { "Google OAuth 未返回授权码" }
        return exchangeCode(code, pending)
    }

    private fun openInBrowser(uri: Uri) {
        try {
            CustomTabsIntent.Builder()
                .build()
                .launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            if (context !is Activity) browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(browserIntent)
        }
    }

    private suspend fun exchangeCode(
        code: String,
        pending: PendingAuthorization,
    ): GoogleOAuthBrowserToken = withContext(Dispatchers.IO) {
        val connection = URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            val requestBody = formBody(
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to pending.verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI.toString(),
            )
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (statusCode !in 200..299) {
                throw IOException("Google OAuth token exchange failed: HTTP $statusCode ${errorMessage(responseBody)}")
            }
            val response = JSONObject(responseBody)
            val accessToken = response.optString("access_token").trim()
            check(accessToken.isNotBlank()) { "Google OAuth token response 未返回 access_token" }
            val expiresInSeconds = response.optLong("expires_in", 0L)
            GoogleOAuthBrowserToken(
                accessToken = accessToken,
                expiresAtMillis = expiresInSeconds.takeIf { it > 0 }?.let {
                    System.currentTimeMillis() + it * 1_000L
                },
                grantedScopes = response.optString("scope")
                    .splitToSequence(' ')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
                    .ifEmpty { pending.scopes.toSet() },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun savePendingAuthorization(
        state: String,
        verifier: String,
        scopes: List<String>,
    ) {
        preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_SCOPES, scopes.joinToString(SCOPE_SEPARATOR))
            .apply()
    }

    private fun readPendingAuthorization(): PendingAuthorization? {
        val state = preferences.getString(KEY_STATE, null)?.takeIf(String::isNotBlank) ?: return null
        val verifier = preferences.getString(KEY_VERIFIER, null)?.takeIf(String::isNotBlank) ?: return null
        val scopes = preferences.getString(KEY_SCOPES, null)
            ?.split(SCOPE_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return PendingAuthorization(state, verifier, scopes)
    }

    private fun clearPendingAuthorization() {
        preferences.edit()
            .remove(KEY_STATE)
            .remove(KEY_VERIFIER)
            .remove(KEY_SCOPES)
            .apply()
    }

    private fun randomUrlSafeValue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun codeChallenge(verifier: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun formBody(vararg values: Pair<String, String>): String = values.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun errorMessage(responseBody: String): String = runCatching {
        val response = JSONObject(responseBody)
        response.optString("error_description").ifBlank { response.optString("error") }
    }.getOrDefault(responseBody).ifBlank { "unknown error" }

    private data class PendingAuthorization(
        val state: String,
        val verifier: String,
        val scopes: List<String>,
    )

    private companion object {
        const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        val REDIRECT_URI: Uri = Uri.parse("https://feeluown.github.io/FuoEvolve/oauth2redirect")
        const val PREFERENCES_NAME = "google_oauth_browser"
        const val KEY_STATE = "state"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_SCOPES = "scopes"
        const val SCOPE_SEPARATOR = "\u001f"
        const val NETWORK_TIMEOUT_MILLIS = 15_000
    }
}
