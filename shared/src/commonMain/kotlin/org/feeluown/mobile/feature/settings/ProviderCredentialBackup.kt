package org.feeluown.mobile

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

enum class ProviderCredentialExportMode {
    Encrypted,
    Plaintext,
}

data class ProviderCredentialBackupFile(
    val fileName: String,
    val content: String,
)

data class ProviderCredentialBackupInspection(
    val encrypted: Boolean,
    val providerCount: Int?,
)

data class ProviderCredentialRestoreResult(
    val restoredProviderIds: List<String>,
    val ignoredProviderIds: List<String>,
)

data class ProviderCredentialBackupTarget(
    val providerId: String?,
    val providerName: String,
)

data class ProviderCredentialEncryptedPayload(
    val saltBase64: String,
    val ivBase64: String,
    val payloadBase64: String,
)

/** Platform crypto primitive used by the portable credential-backup format. */
interface ProviderCredentialBackupCrypto {
    fun encrypt(
        plaintext: String,
        password: String,
        iterations: Int,
        saltBytes: Int,
        ivBytes: Int,
        tagBits: Int,
        aad: ByteArray,
    ): ProviderCredentialEncryptedPayload

    fun decrypt(
        password: String,
        saltBase64: String,
        ivBase64: String,
        payloadBase64: String,
        iterations: Int,
        acceptedSaltBytes: IntRange,
        ivBytes: Int,
        tagBits: Int,
        aad: ByteArray,
    ): String
}

/**
 * Portable provider credential backup shared by all platforms.
 *
 * The JSON envelope, versioning, provider serialization and crypto parameters live here so Android
 * and Desktop cannot silently drift into incompatible formats. Platform code supplies only the
 * cryptographic primitive and file picker/storage integration.
 */
open class ProviderCredentialBackup(
    private val credentialStore: ProviderCredentialStore,
    private val providerRegistry: ProviderRegistryRepository,
    private val providerAuth: ProviderAuthRepository,
    private val crypto: ProviderCredentialBackupCrypto,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val _pendingImportContent = MutableStateFlow<String?>(null)
    val pendingImportContent: StateFlow<String?> = _pendingImportContent.asStateFlow()
    private var pendingExportFile: ProviderCredentialBackupFile? = null

    fun stageImport(content: String) {
        _pendingImportContent.value = content
    }

    fun clearPendingImport() {
        _pendingImportContent.value = null
    }

    fun stageExport(file: ProviderCredentialBackupFile) {
        pendingExportFile = file
    }

    fun consumePendingExport(): ProviderCredentialBackupFile? = pendingExportFile.also {
        pendingExportFile = null
    }

    suspend fun export(
        mode: ProviderCredentialExportMode,
        password: String = "",
        providerId: String? = null,
    ): ProviderCredentialBackupFile = withContext(Dispatchers.Default) {
        val providers = providerRegistry.availableProviders()
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
    ): ProviderCredentialRestoreResult = withContext(Dispatchers.Default) {
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
        val knownProviderIds = providerRegistry.availableProviders().mapTo(linkedSetOf()) { it.providerId }
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

    private suspend fun refreshProviderCredentialState(providerId: String, credentials: ProviderCredentials) {
        val headerFileJson = credentials.headerFileJson
        val oauthClientId = credentials.oauthClientId
        val oauthClientSecret = credentials.oauthClientSecret
        val authorization = credentials.authorization
        val cookieHeader = credentials.cookieHeader
        runCatching {
            when {
                !headerFileJson.isNullOrBlank() -> providerAuth.loginWithHeaderFile(
                    providerId = providerId,
                    headerFileJson = headerFileJson,
                )
                credentials.hasOAuthAccess() && !oauthClientId.isNullOrBlank() && !oauthClientSecret.isNullOrBlank() ->
                    providerAuth.loginWithOAuth(
                        providerId = providerId,
                        accessToken = credentials.oauthAccessToken.orEmpty(),
                        refreshToken = credentials.oauthRefreshToken.orEmpty(),
                        expiresAtMillis = credentials.oauthExpiresAtMillis,
                        scope = credentials.oauthScope,
                        clientId = oauthClientId,
                        clientSecret = oauthClientSecret,
                    )
                !authorization.isNullOrBlank() && !cookieHeader.isNullOrBlank() -> providerAuth.loginWithHeaders(
                    providerId = providerId,
                    authorization = authorization,
                    cookie = cookieHeader,
                )
                credentials.cookies.isNotEmpty() -> providerAuth.loginWithCookies(
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
        val encrypted = crypto.encrypt(
            plaintext = plaintext,
            password = password,
            iterations = PBKDF2_ITERATIONS,
            saltBytes = SALT_BYTES,
            ivBytes = GCM_IV_BYTES,
            tagBits = GCM_TAG_BITS,
            aad = AAD,
        )
        val root = buildJsonObject {
            put(FORMAT_FIELD, JsonPrimitive(FORMAT))
            put(VERSION_FIELD, JsonPrimitive(VERSION))
            put(ENCRYPTED_FIELD, JsonPrimitive(true))
            put(KDF_FIELD, buildJsonObject {
                put(NAME_FIELD, JsonPrimitive(PBKDF2_NAME))
                put(ITERATIONS_FIELD, JsonPrimitive(PBKDF2_ITERATIONS))
                put(SALT_FIELD, JsonPrimitive(encrypted.saltBase64))
                put(PASSWORD_ENCODING_FIELD, JsonPrimitive(PASSWORD_ENCODING))
                put(KEY_BITS_FIELD, JsonPrimitive(AES_KEY_BITS))
            })
            put(CIPHER_FIELD, buildJsonObject {
                put(NAME_FIELD, JsonPrimitive(AES_GCM_NAME))
                put(IV_FIELD, JsonPrimitive(encrypted.ivBase64))
                put(TAG_BITS_FIELD, JsonPrimitive(GCM_TAG_BITS))
            })
            put(PAYLOAD_FIELD, JsonPrimitive(encrypted.payloadBase64))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun decrypt(root: JsonObject, password: String): String {
        val kdf = root.objectValue(KDF_FIELD)
        require(kdf.string(NAME_FIELD) == PBKDF2_NAME) { "不支持的备份密钥派生算法" }
        require(kdf.string(PASSWORD_ENCODING_FIELD) == PASSWORD_ENCODING) { "不支持的备份密码编码" }
        require(kdf.int(KEY_BITS_FIELD) == AES_KEY_BITS) { "备份密钥长度无效" }
        val iterations = kdf.int(ITERATIONS_FIELD)
        require(iterations in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) { "备份密钥派生参数无效" }

        val cipherInfo = root.objectValue(CIPHER_FIELD)
        require(cipherInfo.string(NAME_FIELD) == AES_GCM_NAME) { "不支持的备份加密算法" }
        val tagBits = cipherInfo.int(TAG_BITS_FIELD)
        require(tagBits == GCM_TAG_BITS) { "备份认证标签参数无效" }

        return crypto.decrypt(
            password = password,
            saltBase64 = kdf.string(SALT_FIELD),
            ivBase64 = cipherInfo.string(IV_FIELD),
            payloadBase64 = root.string(PAYLOAD_FIELD),
            iterations = iterations,
            acceptedSaltBytes = 16..64,
            ivBytes = GCM_IV_BYTES,
            tagBits = tagBits,
            aad = AAD,
        )
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
        const val AES_GCM_NAME = "AES-256-GCM"
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val SALT_BYTES = 16
        const val GCM_IV_BYTES = 12
        const val MIN_PASSWORD_LENGTH = 8
        const val ENCRYPTED_FILE_NAME = "fuoevolve-provider-credentials.fuoauth.json"
        const val PLAINTEXT_FILE_NAME = "fuoevolve-provider-credentials.plain.json"
        val AAD = "$FORMAT:$VERSION".encodeToByteArray()
    }
}
