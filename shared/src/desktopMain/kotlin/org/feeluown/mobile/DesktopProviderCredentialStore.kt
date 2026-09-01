package org.feeluown.mobile

import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentialStore

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
    desktopProviderCredentialStoreFactory?.invoke() ?: InMemoryProviderCredentialStore()
