package org.feeluown.mobile

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.feeluown.mobile.provider.core.ProviderCredentialStore

/** Android adapter for the shared portable credential-backup implementation. */
internal class AndroidProviderCredentialBackup(
    credentialStore: ProviderCredentialStore,
    providerRegistry: ProviderRegistryRepository,
    providerAuth: ProviderAuthRepository,
) : ProviderCredentialBackup(
    credentialStore = credentialStore,
    providerRegistry = providerRegistry,
    providerAuth = providerAuth,
    crypto = AndroidProviderCredentialBackupCrypto,
)

private object AndroidProviderCredentialBackupCrypto : ProviderCredentialBackupCrypto {
    override fun encrypt(
        plaintext: String,
        password: String,
        iterations: Int,
        saltBytes: Int,
        ivBytes: Int,
        tagBits: Int,
        aad: ByteArray,
    ): ProviderCredentialEncryptedPayload {
        val salt = ByteArray(saltBytes).also(SecureRandom()::nextBytes)
        val iv = ByteArray(ivBytes).also(SecureRandom()::nextBytes)
        val keyBytes = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(tagBits, iv))
            cipher.updateAAD(aad)
            val payload = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return ProviderCredentialEncryptedPayload(
                saltBase64 = base64(salt),
                ivBase64 = base64(iv),
                payloadBase64 = base64(payload),
            )
        } finally {
            keyBytes.fill(0)
        }
    }

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
    ): String {
        val salt = decodeBase64(saltBase64, "备份盐值无效")
        require(salt.size in acceptedSaltBytes) { "备份盐值无效" }
        val iv = decodeBase64(ivBase64, "备份 IV 无效")
        require(iv.size == ivBytes) { "备份 IV 无效" }
        val encrypted = decodeBase64(payloadBase64, "备份内容无效")

        val keyBytes = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(tagBits, iv))
            cipher.updateAAD(aad)
            return try {
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("密码错误或备份文件已损坏")
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "PBKDF2 iterations must be positive" }
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val blockInput = ByteArray(salt.size + PBKDF2_BLOCK_INDEX_BYTES)
        salt.copyInto(blockInput)
        blockInput[blockInput.lastIndex] = 1
        val mac = Mac.getInstance(HMAC_SHA256)
        try {
            mac.init(SecretKeySpec(passwordBytes, HMAC_SHA256))
            var u = mac.doFinal(blockInput)
            var next = ByteArray(u.size)
            val derived = u.copyOf()
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(next, 0)
                for (index in derived.indices) {
                    derived[index] = (derived[index].toInt() xor next[index].toInt()).toByte()
                }
                val previous = u
                u = next
                next = previous
            }
            u.fill(0)
            next.fill(0)
            return derived
        } finally {
            passwordBytes.fill(0)
            blockInput.fill(0)
        }
    }

    private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decodeBase64(value: String, error: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(error)
    }

    private const val PBKDF2_BLOCK_INDEX_BYTES = 4
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
}
