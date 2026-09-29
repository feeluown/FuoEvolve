package org.feeluown.mobile

/**
 * Wire contract of the ColorOS lock-screen lyric module's Universal Player Provider bindings.
 *
 * Every value must match the module side exactly; a typo fails silently at runtime, because the
 * module simply logs an identity mismatch or ignores an unknown action.
 */
internal object ColorOsBridgeContract {
    const val BRIDGE_PACKAGE_NAME = "io.github.andrealtb.lockscreenlyrics"
    const val BINDINGS_RECEIVER_CLASS_NAME =
        "io.github.andrealtb.lockscreenlyrics.UniversalPlayerBindingsReceiver"
    const val ACTION_PLAYER_BINDINGS_CHANGED =
        "io.github.andrealtb.universallyrics.action.PLAYER_BINDINGS_CHANGED"
    const val EXTRA_BOUND_PACKAGES = "extra_bound_packages"
    const val EXTRA_PROVIDER_IDENTITY = "extra_provider_identity"
    const val PROVIDER_IDENTITY = "universal-player-provider"

    /**
     * Packages the module should admit as host players. This app is both the host player and the
     * `lyricInfo` producer, so it admits itself.
     */
    fun boundPackages(packageName: String): List<String> =
        packageName.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
}
