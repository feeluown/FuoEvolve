package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.feeluown.mobile.ProviderAuthorizationCodeRedirect

/**
 * A WeChat QR cannot be scanned from the phone showing it, and QQ's authorize page does not render in
 * every web view, so the authorization redirect is also accepted as pasted text.
 */
class ProviderAuthorizationCodeParsingTest {
    private val redirect = ProviderAuthorizationCodeRedirect(
        host = "y.qq.com",
        path = "/portal/wx_redirect.html",
        codeParameter = "code",
        loginTypeParameter = "login_type",
    )

    @Test
    fun parsesCodeAndAccountEcosystemFromTheRedirectUrl() {
        val parsed = parseProviderAuthorizationCode(
            "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https%3A%2F%2Fy.qq.com%2F" +
                "&code=ABCDEF0123456789&state=state",
            redirect,
        )

        assertEquals("ABCDEF0123456789" to 2, parsed)
    }

    @Test
    fun treatsAMissingLoginTypeAsUnspecified() {
        val parsed = parseProviderAuthorizationCode(
            "https://y.qq.com/portal/wx_redirect.html?code=ABCDEF0123456789",
            redirect,
        )

        assertEquals("ABCDEF0123456789" to null, parsed)
    }

    @Test
    fun acceptsABareAuthorizationCode() {
        assertEquals("ABCDEF0123456789" to null, parseProviderAuthorizationCode("  ABCDEF0123456789 ", redirect))
    }

    @Test
    fun rejectsTextWithoutAnAuthorizationCode() {
        assertNull(parseProviderAuthorizationCode("", redirect))
        assertNull(parseProviderAuthorizationCode("https://y.qq.com/portal/wx_redirect.html?login_type=2", redirect))
        assertNull(parseProviderAuthorizationCode("随便一段文字", redirect))
    }
}
