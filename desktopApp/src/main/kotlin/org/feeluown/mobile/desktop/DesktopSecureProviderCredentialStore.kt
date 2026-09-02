package org.feeluown.mobile.desktop

import com.microsoft.credentialstorage.SecretStore
import com.microsoft.credentialstorage.StorageProvider
import com.microsoft.credentialstorage.StorageProvider.SecureOption
import com.microsoft.credentialstorage.implementation.posix.libsecret.LibSecretBackedTokenStore
import com.microsoft.credentialstorage.implementation.posix.libsecret.LibSecretLibrary
import com.microsoft.credentialstorage.model.StoredToken
import com.microsoft.credentialstorage.model.StoredTokenType
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials

internal class DesktopSecureProviderCredentialStore(
    private val secretStoreProvider: () -> DesktopSecretStore? = ::createMicrosoftSecretStore,
    private val generationProvider: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : ProviderCredentialStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var secretStoreResolved = false
    private var secretStore: DesktopSecretStore? = null

    override suspend fun read(providerId: String): ProviderCredentials? = mutex.withLock {
        val store = resolveSecretStore() ?: return@withLock null
        val manifest = readManifest(store, providerId) ?: return@withLock null
        val serialized = buildString {
            repeat(manifest.chunkCount) { index ->
                val chunk = readSecret(store, chunkKey(providerId, manifest.generation, index))
                    ?: return@withLock null
                append(chunk)
            }
        }
        runCatching {
            json.decodeFromString(ProviderCredentials.serializer(), serialized)
        }.getOrNull()
    }

    override suspend fun write(providerId: String, credentials: ProviderCredentials) {
        mutex.withLock {
            val store = requireSecretStore()
            val previous = readManifest(store, providerId)
            val serialized = json.encodeToString(ProviderCredentials.serializer(), credentials)
            val generation = generationProvider()
            val chunks = serialized.chunked(SECRET_CHUNK_CHAR_LIMIT).ifEmpty { listOf("") }
            val writtenKeys = mutableListOf<String>()

            try {
                chunks.forEachIndexed { index, value ->
                    val key = chunkKey(providerId, generation, index)
                    check(writeSecret(store, key, value)) { "系统安全凭证存储写入失败" }
                    writtenKeys += key
                }
                val manifest = DesktopCredentialManifest(generation, chunks.size)
                check(writeSecret(store, manifestKey(providerId), manifest.encode())) {
                    "系统安全凭证存储索引写入失败"
                }
            } catch (throwable: Throwable) {
                writtenKeys.forEach(store::delete)
                throw throwable
            }

            previous
                ?.takeIf { it.generation != generation }
                ?.let { removeGeneration(store, providerId, it) }
        }
    }

    override suspend fun delete(providerId: String) = mutex.withLock {
        val store = resolveSecretStore() ?: return@withLock
        readManifest(store, providerId)?.let { removeGeneration(store, providerId, it) }
        store.delete(manifestKey(providerId))
    }

    private fun resolveSecretStore(): DesktopSecretStore? {
        if (!secretStoreResolved) {
            secretStore = runCatching(secretStoreProvider).getOrNull()
            secretStoreResolved = true
        }
        return secretStore
    }

    private fun requireSecretStore(): DesktopSecretStore = resolveSecretStore()
        ?: throw IllegalStateException(
            "系统安全凭证存储不可用。Windows 需要 Credential Manager，macOS 需要 Keychain，Linux 需要可用的 Secret Service/Libsecret。",
        )

    private fun readManifest(store: DesktopSecretStore, providerId: String): DesktopCredentialManifest? =
        readSecret(store, manifestKey(providerId))?.let(DesktopCredentialManifest::decode)

    private fun removeGeneration(
        store: DesktopSecretStore,
        providerId: String,
        manifest: DesktopCredentialManifest,
    ) {
        repeat(manifest.chunkCount) { index ->
            store.delete(chunkKey(providerId, manifest.generation, index))
        }
    }
}

internal interface DesktopSecretStore {
    fun get(key: String): CharArray?
    fun put(key: String, value: CharArray): Boolean
    fun delete(key: String): Boolean
}

private class MicrosoftDesktopSecretStore(
    private val delegate: SecretStore<StoredToken>,
) : DesktopSecretStore {
    override fun get(key: String): CharArray? {
        val token = delegate.get(key) ?: return null
        return try {
            token.value.copyOf()
        } finally {
            token.clear()
        }
    }

    override fun put(key: String, value: CharArray): Boolean {
        val token = StoredToken(value, StoredTokenType.PERSONAL)
        return try {
            delegate.add(key, token)
        } finally {
            token.clear()
        }
    }

    override fun delete(key: String): Boolean = delegate.delete(key)
}

private fun createMicrosoftSecretStore(): DesktopSecretStore? =
    if (System.getProperty("os.name") == "Linux") {
        createLinuxLibSecretStore()
    } else {
        StorageProvider.getTokenStorage(true, SecureOption.REQUIRED)
            ?.takeIf { it.isSecure }
            ?.let(::MicrosoftDesktopSecretStore)
    }

private fun createLinuxLibSecretStore(): DesktopSecretStore? = runCatching {
    // StorageProvider performs a preflight that rejects a locked default collection before
    // normal libsecret interaction can display the system unlock prompt. Use its underlying
    // libsecret store directly on Linux instead: reads request SECRET_SEARCH_UNLOCK and writes
    // use libsecret's default collection, so the Secret Service can handle user interaction.
    LibSecretLibrary.INSTANCE
    MicrosoftDesktopSecretStore(LibSecretBackedTokenStore())
}.getOrNull()

private data class DesktopCredentialManifest(
    val generation: String,
    val chunkCount: Int,
) {
    fun encode(): String = "$MANIFEST_VERSION:$generation:$chunkCount"

    companion object {
        fun decode(value: String): DesktopCredentialManifest? {
            val parts = value.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != MANIFEST_VERSION) return null
            val generation = parts[1].takeIf { it.isNotBlank() } ?: return null
            val chunkCount = parts[2].toIntOrNull()?.takeIf { it in 1..MAX_SECRET_CHUNKS } ?: return null
            return DesktopCredentialManifest(generation, chunkCount)
        }
    }
}

private fun readSecret(store: DesktopSecretStore, key: String): String? {
    val chars = store.get(key) ?: return null
    return try {
        chars.concatToString()
    } finally {
        chars.fill('\u0000')
    }
}

private fun writeSecret(store: DesktopSecretStore, key: String, value: String): Boolean {
    val chars = value.toCharArray()
    return try {
        store.put(key, chars)
    } finally {
        chars.fill('\u0000')
    }
}

private fun manifestKey(providerId: String): String = "$SECRET_KEY_PREFIX.${providerKey(providerId)}.manifest"

private fun chunkKey(providerId: String, generation: String, index: Int): String =
    "$SECRET_KEY_PREFIX.${providerKey(providerId)}.$generation.$index"

private fun providerKey(providerId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(providerId.encodeToByteArray())
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val SECRET_KEY_PREFIX = "org.feeluown.mobile.provider.credentials.v1"
private const val MANIFEST_VERSION = "v1"
private const val SECRET_CHUNK_CHAR_LIMIT = 768
private const val MAX_SECRET_CHUNKS = 256
