package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopProviderWebLoginTest {
    @Test
    fun includesKnownProviderCookieHosts() {
        val urls = desktopWebLoginCookieUrls(
            providerId = "qqmusic",
            loginUrl = "https://y.qq.com",
            currentUrl = "https://ptlogin2.qq.com/check_sig",
        )

        assertTrue("https://qq.com" in urls)
        assertTrue("https://ptlogin2.qq.com/check_sig" in urls)
        assertTrue(urls.distinct().size == urls.size)
    }

    @Test
    fun matchesAnyCompleteCookieGroup() {
        assertTrue(
            hasRequiredDesktopWebLoginCookies(
                cookies = mapOf("MUSIC_U" to "session"),
                cookieKeyGroups = listOf(
                    listOf("MUSIC_U"),
                    listOf("uin", "skey"),
                ),
            ),
        )
    }

    @Test
    fun rejectsIncompleteCookieGroups() {
        assertFalse(
            hasRequiredDesktopWebLoginCookies(
                cookies = mapOf("uin" to "account"),
                cookieKeyGroups = listOf(listOf("uin", "skey")),
            ),
        )
    }
}
