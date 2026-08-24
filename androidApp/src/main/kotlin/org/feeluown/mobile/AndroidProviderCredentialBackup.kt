package org.feeluown.mobile

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class ProviderCredentialExportMode {
    Encrypted,
    Plaintext,
}

internal data class ProviderCredentialBackupFile(
    val fileName: String,
    val content: String,
)

internal data class ProviderCredentialBackupInspection(
    val encrypted: Boolean,
    val providerCount: Int?,
)

internal data class ProviderCredentialRestoreResult(
    val restoredProviderIds: List<String>,
    val ignoredProviderIds: List<String>,
)

internal data class ProviderCredentialBackupTarget(
    val providerId: String?,
    val providerName: String,
)

/**
 * Portable provider credential backup owned by the Android app container.
 *
 * The normal on-device credential store remains protected by Android Keystore. Exported encrypted
 * backups deliberately use a password-derived key instead so the file can be restored on another
 * device. Plaintext export exists only for interoperability and is guarded by an explicit UI risk
 * acknowledgement.
 *
 * Pending picker payloads live here rather than in Activity composition. This object is process
 * scoped, so an Activity recreation while Android's document picker is open cannot discard a
 * selected import or leave a newly created export document empty.
 */
internal class AndroidProviderCredentialBackup(
    private val credentialStore: ProviderCredentialStore,
    private val providerRepository: ProviderMusicRepository,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val _pendingImportContent = MutableStateFlow<String?>(null)
    val pendingImportContent: StateFlow<String?> = _pendingImportContent.asStateFlow()
    private val pendingExportLock = Any()
    private var pendingExportFile: ProviderCredentialBackupFile? = null

    fun stageImport(content: String) {
        _pendingImportContent.value = content
    }

    fun clearPendingImport() {
        _pendingImportContent.value = null
    }

    fun stageExport(file: ProviderCredentialBackupFile) {
        synchronized(pendingExportLock) {
            pendingExportFile = file
        }
    }

    fun consumePendingExport(): ProviderCredentialBackupFile? = synchronized(pendingExportLock) {
        pendingExportFile.also { pendingExportFile = null }
    }

    suspend fun export(
        mode: ProviderCredentialExportMode,
        password: String = "",
        providerId: String? = null,
    ): ProviderCredentialBackupFile = withContext(Dispatchers.IO) {
        val providers = providerRepository.availableProviders()
        val selectedProviders = if (providerId == null) {
            providers
        } else {
            val provider = providers.firstOrNull { it.providerId == providerId }
                ?: throw IllegalArgumentException("当前版本不支持该音源")
            listOf(provider)
        }
        val credentials = buildMap {
            selectedProviders.forEach { provider ->
                credentialStore.read(provider.providerId)?.let { put(provider.providerId, it) }
            }
        }
        if (providerId == null) {
            require(credentials.isNotEmpty()) { "没有可备份的登录凭证" }
        } else {
            val providerName = selectedProviders.first().providerName
            require(credentials.isNotEmpty()) { "$providerName 没有可导出的登录凭证" }
        }

        val plaintext = encodePlaintext(credentials)
        when (mode) {
            ProviderCredentialExportMode.Plaintext -> ProviderCredentialBackupFile(
                fileName = backupFileName(providerId, encrypted = false),
                content = plaintext,
            )
            ProviderCredentialExportMode.Encrypted -> {
                require(password.length >= MIN_PASSWORD_LENGTH) { "备份密码至少需要 $MIN_PASSWORD_LENGTH 位" }
                ProviderCredentialBackupFile(
                    fileName = backupFileName(providerId, encrypted = true),
                    content = encrypt(plaintext, password),
                )
            }
        }
    }

    suspend fun inspect(content: String): ProviderCredentialBackupInspection = withContext(Dispatchers.Default) {
        val root = parseRoot(content)
        validateHeader(root)
        val encrypted = root.boolean(ENCRYPTED_FIELD)
        ProviderCredentialBackupInspection(
            encrypted = encrypted,
            providerCount = if (encrypted) null else providersObject(root).size,
        )
    }

    suspend fun restore(
        content: String,
        password: String = "",
    ): ProviderCredentialRestoreResult = withContext(Dispatchers.IO) {
        val outer = parseRoot(content)
        validateHeader(outer)
        val plaintextRoot = if (outer.boolean(ENCRYPTED_FIELD)) {
            require(password.isNotBlank()) { "请输入备份密码" }
            parseRoot(decrypt(outer, password))
        } else {
            outer
        }
        validateHeader(plaintextRoot)
        require(!plaintextRoot.boolean(ENCRYPTED_FIELD)) { "备份文件结构无效" }

        val providers = providersObject(plaintextRoot)
        require(providers.isNotEmpty()) { "备份中没有登录凭证" }
        val knownProviderIds = providerRepository.availableProviders().mapTo(linkedSetOf()) { it.providerId }
        val decoded = providers.mapValues { (providerId, element) ->
            require(element is JsonObject) { "$providerId 登录凭证格式无效" }
            json.decodeFromJsonElement(ProviderCredentials.serializer(), element)
        }
        val restorable = decoded.filterKeys(knownProviderIds::contains)
        val ignored = decoded.keys.filterNot(knownProviderIds::contains)
        require(restorable.isNotEmpty()) { "备份中没有当前版本支持的音源凭证" }

        restorable.forEach { (providerId, credentials) -> credentialStore.write(providerId, credentials) }
        restorable.forEach { (providerId, credentials) ->
            refreshProviderCredentialState(providerId, credentials)
        }

        ProviderCredentialRestoreResult(
            restoredProviderIds = restorable.keys.toList(),
            ignoredProviderIds = ignored,
        )
    }

    /**
     * Re-enter the provider-owned authentication path after the durable credential write. Providers
     * can invalidate cached identity/session state here (for example YouTube Music's account name).
     * The durable restore remains successful even if a provider refresh needs network access and
     * fails while the device is offline.
     */
    private suspend fun refreshProviderCredentialState(providerId: String, credentials: ProviderCredentials) {
        val headerFileJson = credentials.headerFileJson
        val oauthClientId = credentials.oauthClientId
        val oauthClientSecret = credentials.oauthClientSecret
        val authorization = credentials.authorization
        val cookieHeader = credentials.cookieHeader
        runCatching {
            when {
                !headerFileJson.isNullOrBlank() -> providerRepository.loginWithHeaderFile(
                    providerId = providerId,
                    headerFileJson = headerFileJson,
                )
                credentials.hasOAuthAccess() && !oauthClientId.isNullOrBlank() && !oauthClientSecret.isNullOrBlank() ->
                    providerRepository.loginWithOAuth(
                        providerId = providerId,
                        accessToken = credentials.oauthAccessToken.orEmpty(),
                        refreshToken = credentials.oauthRefreshToken.orEmpty(),
                        expiresAtMillis = credentials.oauthExpiresAtMillis,
                        scope = credentials.oauthScope,
                        clientId = oauthClientId,
                        clientSecret = oauthClientSecret,
                    )
                !authorization.isNullOrBlank() && !cookieHeader.isNullOrBlank() -> providerRepository.loginWithHeaders(
                    providerId = providerId,
                    authorization = authorization,
                    cookie = cookieHeader,
                )
                credentials.cookies.isNotEmpty() -> providerRepository.loginWithCookies(
                    providerId = providerId,
                    cookiesJson = JsonObject(credentials.cookies.mapValues { JsonPrimitive(it.value) }).toString(),
                )
            }
        }
    }

    private fun encodePlaintext(credentials: Map<String, ProviderCredentials>): String {
        val root = buildJsonObject {
            put(FORMAT_FIELD, JsonPrimitive(FORMAT))
            put(VERSION_FIELD, JsonPrimitive(VERSION))
            put(ENCRYPTED_FIELD, JsonPrimitive(false))
            put(
                PROVIDERS_FIELD,
                JsonObject(
                    credentials.mapValues { (_, value) ->
                        json.encodeToJsonElement(ProviderCredentials.serializer(), value)
                    },
                ),
            )
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun encrypt(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val keyBytes = deriveKey(password, salt, PBKDF2_ITERATIONS)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val payload = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val root = buildJsonObject {
                put(FORMAT_FIELD, JsonPrimitive(FORMAT))
                put(VERSION_FIELD, JsonPrimitive(VERSION))
                put(ENCRYPTED_FIELD, JsonPrimitive(true))
                put(KDF_FIELD, buildJsonObject {
                    put(NAME_FIELD, JsonPrimitive(PBKDF2_NAME))
                    put(ITERATIONS_FIELD, JsonPrimitive(PBKDF2_ITERATIONS))
                    put(SALT_FIELD, JsonPrimitive(base64(salt)))
                    put(PASSWORD_ENCODING_FIELD, JsonPrimitive(PASSWORD_ENCODING))
                    put(KEY_BITS_FIELD, JsonPrimitive(AES_KEY_BITS))
                })
                put(CIPHER_FIELD, buildJsonObject {
                    put(NAME_FIELD, JsonPrimitive(AES_GCM_NAME))
                    put(IV_FIELD, JsonPrimitive(base64(iv)))
                    put(TAG_BITS_FIELD, JsonPrimitive(GCM_TAG_BITS))
                })
                put(PAYLOAD_FIELD, JsonPrimitive(base64(payload)))
            }
            return json.encodeToString(JsonObject.serializer(), root)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decrypt(root: JsonObject, password: String): String {
        val kdf = root.objectValue(KDF_FIELD)
        require(kdf.string(NAME_FIELD) == PBKDF2_NAME) { "不支持的备份密钥派生算法" }
        require(kdf.string(PASSWORD_ENCODING_FIELD) == PASSWORD_ENCODING) { "不支持的备份密码编码" }
        require(kdf.int(KEY_BITS_FIELD) == AES_KEY_BITS) { "备份密钥长度无效" }
        val iterations = kdf.int(ITERATIONS_FIELD)
        require(iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) { "备份密钥派生参数无效" }
        val salt = decodeBase64(kdf.string(SALT_FIELD), "备份盐值无效")
        require(salt.size in 16..64) { "备份盐值无效" }

        val cipherInfo = root.objectValue(CIPHER_FIELD)
        require(cipherInfo.string(NAME_FIELD) == AES_GCM_NAME) { "不支持的备份加密算法" }
        require(cipherInfo.int(TAG_BITS_FIELD) == GCM_TAG_BITS) { "备份认证标签参数无效" }
        val iv = decodeBase64(cipherInfo.string(IV_FIELD), "备份 IV 无效")
        require(iv.size == GCM_IV_BYTES) { "备份 IV 无效" }
        val encrypted = decodeBase64(root.string(PAYLOAD_FIELD), "备份内容无效")

        val keyBytes = deriveKey(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            return try {
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("密码错误或备份文件已损坏")
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 implemented with HmacSHA256 so it also works on API 24-25. */
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

    private fun backupFileName(providerId: String?, encrypted: Boolean): String {
        if (providerId == null) return if (encrypted) ENCRYPTED_FILE_NAME else PLAINTEXT_FILE_NAME
        val safeProviderId = providerId.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.') character else '_'
        }.joinToString("").ifBlank { "provider" }
        return if (encrypted) {
            "fuoevolve-provider-credentials-$safeProviderId.fuoauth.json"
        } else {
            "fuoevolve-provider-credentials-$safeProviderId.plain.json"
        }
    }

    private fun parseRoot(content: String): JsonObject = try {
        json.parseToJsonElement(content).jsonObject
    } catch (_: Throwable) {
        throw IllegalArgumentException("无法识别登录凭证备份文件")
    }

    private fun validateHeader(root: JsonObject) {
        require(root.string(FORMAT_FIELD) == FORMAT) { "不是 FuoEvolve 登录凭证备份" }
        require(root.int(VERSION_FIELD) == VERSION) { "暂不支持此版本的登录凭证备份" }
        require(root[ENCRYPTED_FIELD]?.jsonPrimitive?.booleanOrNull != null) { "备份文件缺少加密标记" }
    }

    private fun providersObject(root: JsonObject): JsonObject =
        root[PROVIDERS_FIELD] as? JsonObject ?: throw IllegalArgumentException("备份文件缺少音源登录凭证")

    private fun JsonObject.objectValue(name: String): JsonObject =
        this[name] as? JsonObject ?: throw IllegalArgumentException("备份文件缺少 $name")

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("备份文件缺少 $name")

    private fun JsonObject.int(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("备份文件缺少 $name")

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: throw IllegalArgumentException("备份文件缺少 $name")

    private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decodeBase64(value: String, error: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(error)
    }

    private companion object {
        const val FORMAT = "fuoevolve-provider-credentials"
        const val VERSION = 1
        const val FORMAT_FIELD = "format"
        const val VERSION_FIELD = "version"
        const val ENCRYPTED_FIELD = "encrypted"
        const val PROVIDERS_FIELD = "providers"
        const val KDF_FIELD = "kdf"
        const val CIPHER_FIELD = "cipher"
        const val PAYLOAD_FIELD = "payload"
        const val NAME_FIELD = "name"
        const val ITERATIONS_FIELD = "iterations"
        const val SALT_FIELD = "salt"
        const val PASSWORD_ENCODING_FIELD = "passwordEncoding"
        const val KEY_BITS_FIELD = "keyBits"
        const val IV_FIELD = "iv"
        const val TAG_BITS_FIELD = "tagBits"
        const val PBKDF2_NAME = "PBKDF2-HMAC-SHA256"
        const val PASSWORD_ENCODING = "UTF-8"
        const val PBKDF2_ITERATIONS = 210_000
        const val MIN_ACCEPTED_ITERATIONS = 100_000
        const val MAX_ACCEPTED_ITERATIONS = 1_000_000
        const val PBKDF2_BLOCK_INDEX_BYTES = 4
        const val HMAC_SHA256 = "HmacSHA256"
        const val AES_GCM_NAME = "AES-256-GCM"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val MIN_PASSWORD_LENGTH = 8
        const val ENCRYPTED_FILE_NAME = "fuoevolve-provider-credentials.fuoauth.json"
        const val PLAINTEXT_FILE_NAME = "fuoevolve-provider-credentials.plain.json"
        val AAD = "$FORMAT:$VERSION".toByteArray(Charsets.UTF_8)
    }
}
