package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.provider.core.ProviderCredentials

class DesktopSecureProviderCredentialStoreTest {
    @Test
    fun roundTripsLargeCredentialsAcrossMultipleSecrets() = runBlocking {
        val backend = FakeDesktopSecretStore()
        val store = DesktopSecureProviderCredentialStore(
            secretStoreProvider = { backend },
            generationProvider = { "generation1" },
        )
        val credentials = largeCredentials()

        store.write("netease", credentials)

        assertEquals(credentials, store.read("netease"))
        assertTrue(backend.values.size > 2, "large credential should use manifest plus multiple chunks")
        assertTrue(backend.values.keys.any { it.endsWith(".manifest") })
    }

    @Test
    fun successfulOverwriteSwitchesGenerationAndRemovesOldChunks() = runBlocking {
        val backend = FakeDesktopSecretStore()
        val generations = ArrayDeque(listOf("generation1", "generation2"))
        val store = DesktopSecureProviderCredentialStore(
            secretStoreProvider = { backend },
            generationProvider = { generations.removeFirst() },
        )
        val first = largeCredentials(cookiePrefix = "first")
        val second = largeCredentials(cookiePrefix = "second")

        store.write("qqmusic", first)
        assertTrue(backend.values.keys.any { ".generation1." in it })

        store.write("qqmusic", second)

        assertEquals(second, store.read("qqmusic"))
        assertTrue(backend.values.keys.none { ".generation1." in it })
        assertTrue(backend.values.keys.any { ".generation2." in it })
    }

    @Test
    fun failedManifestWritePreservesPreviousGeneration() = runBlocking {
        val backend = FakeDesktopSecretStore()
        val generations = ArrayDeque(listOf("generation1", "generation2"))
        val store = DesktopSecureProviderCredentialStore(
            secretStoreProvider = { backend },
            generationProvider = { generations.removeFirst() },
        )
        val first = largeCredentials(cookiePrefix = "stable")
        val second = largeCredentials(cookiePrefix = "replacement")

        store.write("bilibili", first)
        backend.failManifestWrites = true

        assertFailsWith<IllegalStateException> {
            store.write("bilibili", second)
        }

        backend.failManifestWrites = false
        assertEquals(first, store.read("bilibili"))
        assertTrue(backend.values.keys.none { ".generation2." in it })
        assertTrue(backend.values.keys.any { ".generation1." in it })
    }

    @Test
    fun deleteRemovesManifestAndAllActiveChunks() = runBlocking {
        val backend = FakeDesktopSecretStore()
        val store = DesktopSecureProviderCredentialStore(
            secretStoreProvider = { backend },
            generationProvider = { "generation1" },
        )

        store.write("ytmusic", largeCredentials())
        store.delete("ytmusic")

        assertNull(store.read("ytmusic"))
        assertTrue(backend.values.isEmpty())
    }

    @Test
    fun unavailableSecureStoreNeverFallsBackToPlaintextPersistence() = runBlocking {
        val store = DesktopSecureProviderCredentialStore(secretStoreProvider = { null })

        assertNull(store.read("netease"))
        val error = assertFailsWith<IllegalStateException> {
            store.write("netease", ProviderCredentials(cookieHeader = "MUSIC_U=secret"))
        }
        assertTrue(error.message.orEmpty().contains("系统安全凭证存储不可用"))
    }

    private fun largeCredentials(cookiePrefix: String = "cookie"): ProviderCredentials = ProviderCredentials(
        cookies = (1..80).associate { index ->
            "key$index" to "$cookiePrefix-$index-${"x".repeat(48)}"
        },
        authorization = "Bearer ${"a".repeat(400)}",
        cookieHeader = "raw=${"b".repeat(500)}",
        headerFileJson = "{\"headers\":\"${"c".repeat(700)}\"}",
        oauthAccessToken = "access-${"d".repeat(300)}",
        oauthRefreshToken = "refresh-${"e".repeat(300)}",
        oauthExpiresAtMillis = 1_900_000_000_000,
        oauthScope = "scope-a scope-b",
        oauthClientId = "desktop-client",
        oauthClientSecret = "secret-${"f".repeat(300)}",
    )
}

private class FakeDesktopSecretStore : DesktopSecretStore {
    val values = linkedMapOf<String, CharArray>()
    var failManifestWrites = false

    override fun get(key: String): CharArray? = values[key]?.copyOf()

    override fun put(key: String, value: CharArray): Boolean {
        if (failManifestWrites && key.endsWith(".manifest")) return false
        values[key] = value.copyOf()
        return true
    }

    override fun delete(key: String): Boolean = values.remove(key) != null
}
