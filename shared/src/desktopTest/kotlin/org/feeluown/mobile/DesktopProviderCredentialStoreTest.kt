package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.provider.core.ProviderCredentials

class DesktopProviderCredentialStoreTest {
    @Test
    fun missingHostFactoryDoesNotSilentlyPersistCredentialsInMemory() = runBlocking {
        val store = createDesktopProviderCredentialStore()

        assertNull(store.read("netease"))
        val error = assertFailsWith<IllegalStateException> {
            store.write("netease", ProviderCredentials(cookieHeader = "MUSIC_U=secret"))
        }
        assertTrue(error.message.orEmpty().contains("desktopApp"))
    }
}
