package org.feeluown.mobile.desktop

import com.microsoft.credentialstorage.SecretStore
import com.microsoft.credentialstorage.StorageProvider
import com.microsoft.credentialstorage.StorageProvider.SecureOption
import com.microsoft.credentialstorage.implementation.posix.libsecret.LibSecretBackedTokenStore
import com.microsoft.credentialstorage.implementation.posix.libsecret.LibSecretLibrary
import com.microsoft.credentialstorage.model.StoredToken
import com.microsoft.credentialstorage.model.StoredTokenType
import com.sun.jna.NativeLibrary
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials

/** Shared JVM/GraalVM desktop credential entry point. */
fun createDesktopSecureProviderCredentialStore(): ProviderCredentialStore =
    DesktopSecureProviderCredentialStore()

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
            val chunks = chunkCredentialPayload(serialized).ifEmpty { listOf("") }
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
            secretStoreFailure?.let { failure ->
                AppLogger.e(
                    DESKTOP_CREDENTIAL_LOG_TAG,
                    "Failed to initialize desktop secure credential storage",
                    failure,
                )
            }
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

/**
 * credential-secure-storage writes macOS Keychain values through `security -i` and surrounds each
 * argument with quotes, but it does not escape quotes/newlines inside the password itself. Provider
 * credentials are JSON, so passing them through unchanged can make `security add-generic-password`
 * parse the command incorrectly. Encode only the macOS payload before handing it to the library.
 *
 * Prefixing the encoded form lets us continue reading any values that were successfully stored by
 * older builds without encoding.
 */
internal class MacOsSafeDesktopSecretStore(
    private val delegate: DesktopSecretStore,
) : DesktopSecretStore {
    override fun get(key: String): CharArray? {
        val stored = delegate.get(key) ?: return null
        return try {
            decodeMacOsSecret(stored)
        } finally {
            stored.fill('\u0000')
        }
    }

    override fun put(key: String, value: CharArray): Boolean {
        val encoded = encodeMacOsSecret(value)
        return try {
            delegate.put(key, encoded)
        } finally {
            encoded.fill('\u0000')
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
            ?.let { store ->
                if (isMacOs()) MacOsSafeDesktopSecretStore(store) else store
            }
    }

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

private fun createLinuxLibSecretStore(): DesktopSecretStore {
    // Installed packages and portable images must see the same host Secret Service/keyring.
    // Prefer the host client library and only add a bundled directory when it cannot be loaded.
    configureLinuxLibSecretRuntime()

    // StorageProvider rejects a locked default collection before normal libsecret interaction can
    // display the system unlock prompt. Use its underlying store directly so Secret Service owns
    // the interaction and unlock flow.
    LibSecretLibrary.INSTANCE
    return MicrosoftDesktopSecretStore(LibSecretBackedTokenStore())
}

@Volatile
private var hostLibSecretHandle: NativeLibrary? = null

private fun configureLinuxLibSecretRuntime() {
    val hostAvailable = runCatching {
        NativeLibrary.getInstance(LIBSECRET_JNA_NAME).also { library ->
            REQUIRED_LIBSECRET_SYMBOLS.forEach(library::getFunction)
            hostLibSecretHandle = library
        }
    }.isSuccess
    if (hostAvailable) return

    val bundledLibraryDir = System.getProperty("fuoevolve.libsecret.dir")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("FUOEVOLVE_LIBSECRET_DIR")?.takeIf(String::isNotBlank)
        ?: return
    registerBundledLinuxLibSecretSearchPaths(bundledLibraryDir, NativeLibrary::addSearchPath)
}

internal fun registerBundledLinuxLibSecretSearchPaths(
    bundledLibraryDir: String,
    addSearchPath: (String, String) -> Unit,
) {
    if (bundledLibraryDir.isBlank()) return
    BUNDLED_LIBSECRET_JNA_NAMES.forEach { libraryName ->
        addSearchPath(libraryName, bundledLibraryDir)
    }
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

internal fun chunkCredentialPayload(
    value: String,
    maxChars: Int = SECRET_CHUNK_CHAR_LIMIT,
): List<String> {
    require(maxChars >= 2) { "credential chunk size must leave room for a surrogate pair" }
    if (value.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < value.length) {
        var end = minOf(start + maxChars, value.length)
        if (
            end < value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end--
        }
        chunks += value.substring(start, end)
        start = end
    }
    return chunks
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

private fun encodeMacOsSecret(value: CharArray): CharArray {
    val bytes = value.concatToString().encodeToByteArray()
    return try {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        "$MACOS_SECRET_ENCODING_PREFIX$encoded".toCharArray()
    } finally {
        bytes.fill(0)
    }
}

private fun decodeMacOsSecret(value: CharArray): CharArray {
    val stored = value.concatToString()
    if (!stored.startsWith(MACOS_SECRET_ENCODING_PREFIX)) return value.copyOf()

    val encoded = stored.removePrefix(MACOS_SECRET_ENCODING_PREFIX)
    return runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            bytes.toString(Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }.getOrElse {
        // Be tolerant of an unlikely legacy value that happens to use the prefix.
        value.copyOf()
    }
}

private fun manifestKey(providerId: String): String = "$SECRET_KEY_PREFIX.${providerKey(providerId)}.manifest"

private fun chunkKey(providerId: String, generation: String, index: Int): String =
    "$SECRET_KEY_PREFIX.${providerKey(providerId)}.$generation.$index"

private fun providerKey(providerId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(providerId.encodeToByteArray())
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val DESKTOP_CREDENTIAL_LOG_TAG = "DesktopCredentials"
private const val LIBSECRET_JNA_NAME = "secret-1"
private val REQUIRED_LIBSECRET_SYMBOLS = listOf(
    "secret_service_search_sync",
    "secret_password_store_sync",
    "secret_password_clear_sync",
)
private val BUNDLED_LIBSECRET_JNA_NAMES = listOf(
    "secret-1",
    "glib-2.0",
    "gobject-2.0",
    "gio-2.0",
)
private const val MACOS_SECRET_ENCODING_PREFIX = "fuoevolve-b64-v1:"
private const val SECRET_KEY_PREFIX = "org.feeluown.mobile.provider.credentials.v1"
private const val MANIFEST_VERSION = "v1"
private const val SECRET_CHUNK_CHAR_LIMIT = 768
private const val MAX_SECRET_CHUNKS = 256
