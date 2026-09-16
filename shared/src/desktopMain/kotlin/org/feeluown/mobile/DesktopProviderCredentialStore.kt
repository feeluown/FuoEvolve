package org.feeluown.mobile

import org.feeluown.mobile.provider.core.ProviderCredentialStore

@Volatile
private var desktopProviderCredentialStoreFactory: (() -> ProviderCredentialStore)? = null

/** Installs the OS-backed credential store from the JVM host before DesktopAppHost is composed. */
fun installDesktopProviderCredentialStoreFactory(factory: () -> ProviderCredentialStore) {
    desktopProviderCredentialStoreFactory = factory
}

internal fun createDesktopProviderCredentialStore(): ProviderCredentialStore =
    checkNotNull(desktopProviderCredentialStoreFactory) {
        "Desktop secure credential storage must be installed before DesktopAppHost is created"
    }.invoke()
