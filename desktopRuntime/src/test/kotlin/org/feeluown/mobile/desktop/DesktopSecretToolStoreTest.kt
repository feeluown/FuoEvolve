package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSecretToolStoreTest {
    @Test
    fun mapsSecretStoreOperationsToSecretToolArguments() {
        val calls = mutableListOf<SecretToolCall>()
        val store = SecretToolDesktopSecretStore(SecretToolCommandRunner { arguments, input ->
            calls += SecretToolCall(arguments, input?.concatToString())
            when (arguments.first()) {
                "lookup" -> SecretToolCommandResult(exitCode = 0, stdout = "stored-value\n")
                else -> SecretToolCommandResult(exitCode = 0)
            }
        })

        assertTrue(store.put("credential-key", "payload".toCharArray()))
        assertEquals("stored-value", store.get("credential-key")?.concatToString())
        assertTrue(store.delete("credential-key"))

        assertEquals(
            listOf(
                SecretToolCall(
                    listOf(
                        "store",
                        "--label=FuoEvolve provider credentials",
                        "application",
                        "credential-key",
                    ),
                    "payload",
                ),
                SecretToolCall(
                    listOf("lookup", "application", "credential-key"),
                    null,
                ),
                SecretToolCall(
                    listOf("clear", "application", "credential-key"),
                    null,
                ),
            ),
            calls,
        )
    }

    @Test
    fun failedSecretToolLookupBehavesLikeMissingSecret() {
        val store = SecretToolDesktopSecretStore(SecretToolCommandRunner { _, _ ->
            SecretToolCommandResult(exitCode = 1)
        })

        assertNull(store.get("missing-key"))
        assertTrue(!store.put("missing-key", "payload".toCharArray()))
    }
}

private data class SecretToolCall(
    val arguments: List<String>,
    val input: String?,
)
