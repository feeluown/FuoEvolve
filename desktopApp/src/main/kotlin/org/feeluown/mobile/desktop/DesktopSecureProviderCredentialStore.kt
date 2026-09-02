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
    private var secretStoreFailure: Throwable? = null

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
                    check(writeSecret(store, key, value)) { secretWriteFailureMessage("凭证数据") }
                    writtenKeys += key
                }
                val manifest = DesktopCredentialManifest(generation, chunks.size)
                check(writeSecret(store, manifestKey(providerId), manifest.encode())) {
                    secretWriteFailureMessage("凭证索引")
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
            val result = runCatching(secretStoreProvider)
            secretStore = result.getOrNull()
            secretStoreFailure = result.exceptionOrNull()
            secretStoreResolved = true
        }
        return secretStore
    }

    private fun requireSecretStore(): DesktopSecretStore = resolveSecretStore()
        ?: throw IllegalStateException(
            secretStoreUnavailableMessage(secretStoreFailure),
            secretStoreFailure,
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

private fun createLinuxLibSecretStore(): DesktopSecretStore {
    // StorageProvider performs a preflight that rejects a locked default collection before
    // normal libsecret interaction can display the system unlock prompt. Use its underlying
    // libsecret store directly on Linux instead: reads request SECRET_SEARCH_UNLOCK and writes
    // use libsecret's default collection, so the Secret Service can handle user interaction.
    LibSecretLibrary.INSTANCE
    return MicrosoftDesktopSecretStore(LibSecretBackedTokenStore())
}

private fun secretStoreUnavailableMessage(failure: Throwable?): String {
    if (failure == null) {
        return "系统安全凭证存储不可用。Windows 需要 Credential Manager，macOS 需要 Keychain，Linux 需要可用的 Secret Service/Libsecret。"
    }

    val cause = deepestRelevantCause(failure)
    val detail = throwableSummary(cause)
    return if (System.getProperty("os.name") == "Linux") {
        when (cause) {
            is UnsatisfiedLinkError ->
                "Linux 安全凭证存储初始化失败：无法加载 Libsecret/GLib 原生库。请确认已安装 libsecret，且系统能够加载 libsecret-1.so。原因：$detail"
            is NoClassDefFoundError, is ClassNotFoundException ->
                "Linux 安全凭证存储初始化失败：Libsecret/JNA 运行时类加载失败。原因：$detail"
            else ->
                "Linux 安全凭证存储初始化失败：Libsecret 后端创建失败。原因：$detail"
        }
    } else {
        "系统安全凭证存储初始化失败：$detail"
    }
}

private fun secretWriteFailureMessage(target: String): String =
    if (System.getProperty("os.name") == "Linux") {
        "Linux 安全凭证存储写入失败（$target）：Libsecret 已加载，但 Secret Service 未能完成写入。" +
            "请确认 org.freedesktop.secrets 服务可用且默认密钥环已解锁；KDE Plasma 可启用 KWallet 的 Secret Service 接口。"
    } else {
        "系统安全凭证存储写入失败（$target）"
    }

private fun deepestRelevantCause(throwable: Throwable): Throwable {
    val causes = generateSequence(throwable) { current -> current.cause }
        .take(16)
        .toList()
    return causes.firstOrNull { it is UnsatisfiedLinkError }
        ?: causes.firstOrNull { it is NoClassDefFoundError || it is ClassNotFoundException }
        ?: causes.last()
}

private fun throwableSummary(throwable: Throwable): String {
    val type = throwable::class.java.simpleName.ifBlank { throwable::class.java.name }
    val message = throwable.message?.trim()?.takeIf { it.isNotEmpty() }
    return if (message == null) type else "$type: $message"
}

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
