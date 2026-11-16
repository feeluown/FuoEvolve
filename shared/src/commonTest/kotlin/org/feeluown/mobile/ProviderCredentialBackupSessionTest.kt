package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderSessionCredential

/**
 * A renewable provider session (QQ Music `refresh_token`) can mint new access keys for weeks, so it
 * must survive only the password-encrypted backup.
 */
class ProviderCredentialBackupSessionTest {
    private val qqMusic = ProviderInfo(
        providerId = "qqmusic",
        providerName = "QQ 音乐",
    )

    private suspend fun backup(store: InMemoryProviderCredentialStore) = ProviderCredentialBackup(
        credentialStore = store,
        providerRegistry = object : ProviderRegistryRepository {
            override suspend fun initialize() = Unit
            override suspend fun providers(): List<ProviderInfo> = listOf(qqMusic)
        },
        providerAuth = RecordingAuth(),
        crypto = ReversibleCrypto,
    )

    private suspend fun storeWithRenewableSession(): InMemoryProviderCredentialStore =
        InMemoryProviderCredentialStore().apply {
            write(
                "qqmusic",
                ProviderCredentials(
                    cookies = mapOf("uin" to "123456", "qm_keyst" to "auth-key"),
                    session = ProviderSessionCredential(
                        authKey = "auth-key",
                        refreshToken = "refresh-secret",
                        accountId = "123456",
                        authKeyCreatedAtMillis = 1_700_000_000_000L,
                        authKeyExpiresInMillis = 259_200_000L,
                        grantExpiresAtMillis = 1_705_000_000_000L,
                    ),
                ),
            )
        }

    @Test
    fun plaintextExportDropsRenewableSession() = runTest {
        val file = backup(storeWithRenewableSession()).export(ProviderCredentialExportMode.Plaintext)

        assertFalse(file.content.contains("refresh-secret"), "plaintext backup must not leak refresh_token")
        assertFalse(file.content.contains("refreshToken"))
        // The cookie part still round-trips so a plaintext backup stays useful.
        assertTrue(file.content.contains("qm_keyst"))
    }

    @Test
    fun encryptedExportKeepsRenewableSession() = runTest {
        val file = backup(storeWithRenewableSession())
            .export(ProviderCredentialExportMode.Encrypted, password = "correct horse battery")
        val payload = ReversibleCrypto.decode(file.content)

        assertTrue(payload.contains("refresh-secret"), "encrypted backup should avoid a re-scan after restore")
    }

    private class RecordingAuth : ProviderAuthRepository {
        override suspend fun authState(providerId: String): ProviderAuthState = loggedIn(providerId)
        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
            loggedIn(providerId)
        override suspend fun logout(providerId: String): ProviderAuthState = ProviderAuthState(
            providerId = providerId,
            providerName = providerId,
            isLoggedIn = false,
        )

        private fun loggedIn(providerId: String) = ProviderAuthState(
            providerId = providerId,
            providerName = providerId,
            isLoggedIn = true,
        )
    }

    private object ReversibleCrypto : ProviderCredentialBackupCrypto {
        private const val PREFIX = "\"payload\": \""

        override fun encrypt(
            plaintext: String,
            password: String,
            iterations: Int,
            saltBytes: Int,
            ivBytes: Int,
            tagBits: Int,
            aad: ByteArray,
        ): ProviderCredentialEncryptedPayload = ProviderCredentialEncryptedPayload(
            saltBase64 = "c2FsdA==",
            ivBase64 = "aXY=",
            payloadBase64 = hexEncode(plaintext),
        )

        override fun decrypt(
            password: String,
            saltBase64: String,
            ivBase64: String,
            payloadBase64: String,
            iterations: Int,
            acceptedSaltBytes: IntRange,
            ivBytes: Int,
            tagBits: Int,
            aad: ByteArray,
        ): String = hexDecode(payloadBase64)

        /** Reads the hex payload straight out of the envelope for assertions. */
        fun decode(content: String): String {
            val start = content.indexOf(PREFIX)
            require(start >= 0) { "encrypted envelope has no payload" }
            val valueStart = start + PREFIX.length
            val valueEnd = content.indexOf('\"', valueStart)
            return hexDecode(content.substring(valueStart, valueEnd))
        }

        private fun hexEncode(value: String): String = buildString {
            value.encodeToByteArray().forEach { byte ->
                val number = byte.toInt() and 0xff
                append("0123456789abcdef"[number ushr 4])
                append("0123456789abcdef"[number and 0x0f])
            }
        }

        private fun hexDecode(value: String): String {
            val bytes = ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return bytes.decodeToString()
        }
    }
}
