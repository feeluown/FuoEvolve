package org.feeluown.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.providerCredentialsFromCookieInput
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidProviderCredentialStore(
    context: Context,
) : ProviderCredentialStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun read(providerId: String): ProviderCredentials? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(providerId, null) ?: return@withContext null
        runCatching { decode(encoded) }.getOrNull()
    }

    override suspend fun write(providerId: String, credentials: ProviderCredentials) = withContext(Dispatchers.IO) {
        val encoded = encode(credentials)
        check(preferences.edit().putString(providerId, encoded).commit()) {
            "无法保存 $providerId 登录凭据"
        }
    }

    override suspend fun delete(providerId: String) {
        withContext(Dispatchers.IO) {
            preferences.edit().remove(providerId).commit()
        }
    }

    override suspend fun migrateLegacyIfNeeded() {
        val legacy = AndroidLegacySettingsLoader(applicationContext).load()
        legacy.providerCookieInputs.forEach { (providerId, raw) ->
            if (read(providerId) == null) {
                write(providerId, providerCredentialsFromCookieInput(raw))
            }
        }
        legacy.providerHeaderInputs.forEach { (providerId, input) ->
            if (read(providerId) == null && input.authorization.isNotBlank() && input.cookie.isNotBlank()) {
                write(
                    providerId,
                    ProviderCredentials(
                        authorization = input.authorization.trim(),
                        cookieHeader = input.cookie.trim(),
                    ),
                )
            }
        }
    }

    private fun encode(credentials: ProviderCredentials): String {
        val plainText = json.encodeToString(credentials).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText)
        return Base64.encodeToString(
            ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(encrypted)
                .array(),
            Base64.NO_WRAP,
        )
    }

    private fun decode(value: String): ProviderCredentials {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(bytes)
        val ivSize = buffer.int
        require(ivSize in 12..16) { "invalid encrypted credential IV" }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val encrypted = ByteArray(buffer.remaining())
        buffer.get(encrypted)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return json.decodeFromString(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "provider_credentials_v2"
        const val KEY_ALIAS = "org.feeluown.mobile.provider.credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
