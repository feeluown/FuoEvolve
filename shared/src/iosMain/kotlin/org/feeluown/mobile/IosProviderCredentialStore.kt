package org.feeluown.mobile

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.errSecItemNotFound
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.providerCredentialsFromCookieInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
internal class IosProviderCredentialStore : ProviderCredentialStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun read(providerId: String): ProviderCredentials? = memScoped {
        val result = alloc<ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query(providerId, includeData = true), result.ptr.reinterpret())
        if (status == errSecItemNotFound) return@memScoped null
        check(status == 0) { "Keychain read failed: $status" }
        val data = result.value as? NSData ?: return@memScoped null
        val bytes = data.bytes?.readBytes(data.length.toInt()) ?: return@memScoped null
        runCatching { json.decodeFromString<ProviderCredentials>(bytes.decodeToString()) }.getOrNull()
    }

    override suspend fun write(providerId: String, credentials: ProviderCredentials) {
        val value = json.encodeToString(credentials)
        val bytes = value.encodeToByteArray()
        memScoped {
            val data = NSData.Companion.create(
                bytes = bytes.refTo(0).getPointer(this),
                length = bytes.size.toULong(),
            )
            SecItemDelete(query(providerId, includeData = false))
            val attributes = queryMap(providerId, includeData = false).toMutableMap()
            attributes[kSecValueData] = data
            check(SecItemAdd(attributes as platform.CoreFoundation.CFDictionaryRef, null) == 0) {
                "Keychain write failed"
            }
        }
    }

    override suspend fun delete(providerId: String) {
        SecItemDelete(query(providerId, includeData = false))
    }

    override suspend fun migrateLegacyIfNeeded() {
        val legacy = IosLegacySettingsLoader().load()
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

    private fun query(providerId: String, includeData: Boolean): platform.CoreFoundation.CFDictionaryRef {
        return queryMap(providerId, includeData) as platform.CoreFoundation.CFDictionaryRef
    }

    private fun queryMap(providerId: String, includeData: Boolean): MutableMap<Any?, Any?> {
        val result = mutableMapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE,
            kSecAttrAccount to providerId,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        if (includeData) result[kSecReturnData] = kCFBooleanTrue
        return result
    }

    private companion object {
        const val SERVICE = "org.feeluown.mobile.provider.credentials"
    }
}
