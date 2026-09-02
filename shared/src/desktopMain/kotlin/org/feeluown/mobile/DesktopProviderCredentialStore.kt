package org.feeluown.mobile

import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials

@Volatile
private var desktopProviderCredentialStoreFactory: (() -> ProviderCredentialStore)? = null

/**
 * Installs the OS-backed credential store from the JVM host.
 *
 * The native/JNA secure-storage implementation remains in `desktopApp`; provider/common code sees
 * only [ProviderCredentialStore], matching the playback engine composition boundary.
 */
fun installDesktopProviderCredentialStoreFactory(factory: () -> ProviderCredentialStore) {
    desktopProviderCredentialStoreFactory = factory
}

internal fun createDesktopProviderCredentialStore(): ProviderCredentialStore =
    desktopProviderCredentialStoreFactory?.invoke() ?: MissingDesktopProviderCredentialStore

private object MissingDesktopProviderCredentialStore : ProviderCredentialStore {
    override suspend fun read(providerId: String): ProviderCredentials? = null

    override suspend fun write(providerId: String, credentials: ProviderCredentials) {
        throw IllegalStateException("桌面安全凭证存储未由 desktopApp 注入")
    }

    override suspend fun delete(providerId: String) = Unit
}
