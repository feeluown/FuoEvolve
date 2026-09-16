package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopProviderCredentialStoreTest {
    @Test
    fun missingHostFactoryFailsAtCreation() {
        val error = assertFailsWith<IllegalStateException> {
            createDesktopProviderCredentialStore()
        }
        assertTrue(error.message.orEmpty().contains("must be installed"))
    }
}
