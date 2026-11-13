package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform capability for the browser session used by provider web login.
 *
 * Logging out of a provider intentionally keeps that browser state: providers such as QQ Music
 * treat a fresh web session as an unknown device and start answering with 登录环境异常. Clearing it
 * is therefore an explicit, separate action ("使用其他账号登录") surfaced inside the shared UI.
 */
data class ProviderWebLoginStateActions(
    /**
     * Clears the platform web-view cookie jar and web storage so the next web login starts from a
     * signed-out browser session. Null when a platform cannot express this, for example when its
     * web login always runs in a throw-away web view.
     */
    val clearWebLoginState: (() -> Unit)? = null,
) {
    val canClearWebLoginState: Boolean
        get() = clearWebLoginState != null
}

val LocalProviderWebLoginStateActions = staticCompositionLocalOf { ProviderWebLoginStateActions() }
