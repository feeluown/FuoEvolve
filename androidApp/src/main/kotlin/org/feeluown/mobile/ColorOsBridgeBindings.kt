package org.feeluown.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Declares this app as an admitted Universal Player Provider host for the ColorOS lock-screen
 * lyrics module (`io.github.andrealtb.lockscreenlyrics`).
 *
 * The module keeps its own admitted-player union. A player outside the vendor list, and outside the
 * manifest opt-in path, is admitted only through this public bindings contract. Without admission
 * the module still parses our `lyricInfo` payload, but the vendor lyric entrance stays closed, the
 * AOD media panel rejects the package, and the promoted provider package stays empty. That last
 * state is what removes the translation toggle action on every pass and leaves the renderer
 * preference unapplied, so the button can appear without doing anything.
 *
 * The bindings broadcast is sent twice on purpose:
 * - an implicit broadcast reaches the receiver the module registers dynamically in the
 *   `com.android.systemui` process, which is where the visual and toggle hooks actually run;
 * - an explicit broadcast reaches the module's exported manifest receiver, which keeps the module
 *   application's persisted bindings in sync.
 *
 * Addressing the module package with `setPackage` would defeat the first delivery, because that
 * receiving component lives in the SystemUI process rather than in the module package.
 */
internal object ColorOsBridgeBindings {
    private const val TAG = "ColorOsBridgeBindings"

    /**
     * Publishes this app as a bound host player. Delivery cannot be confirmed from here, so the
     * record states that the broadcast was requested rather than that the module accepted it.
     */
    fun publish(context: Context, reason: String) {
        val boundPackages = ArrayList(ColorOsBridgeContract.boundPackages(context.packageName))
        if (boundPackages.isEmpty()) {
            AppLogger.w(TAG, "skipped ColorOS lyric provider bindings without a package name reason=$reason")
            return
        }
        val implicitIntent = Intent(ColorOsBridgeContract.ACTION_PLAYER_BINDINGS_CHANGED)
            .putStringArrayListExtra(ColorOsBridgeContract.EXTRA_BOUND_PACKAGES, boundPackages)
            .putExtra(ColorOsBridgeContract.EXTRA_PROVIDER_IDENTITY, ColorOsBridgeContract.PROVIDER_IDENTITY)
        val explicitIntent = Intent(implicitIntent).setComponent(
            ComponentName(
                ColorOsBridgeContract.BRIDGE_PACKAGE_NAME,
                ColorOsBridgeContract.BINDINGS_RECEIVER_CLASS_NAME,
            ),
        )
        val implicitResult = deliver(context, implicitIntent)
        val explicitResult = deliver(context, explicitIntent)
        AppLogger.i(
            TAG,
            "requested ColorOS lyric provider bindings package=${context.packageName} " +
                "reason=$reason implicit=${implicitResult.isSuccess} explicit=${explicitResult.isSuccess}",
        )
        (implicitResult.exceptionOrNull() ?: explicitResult.exceptionOrNull())?.let { throwable ->
            AppLogger.w(TAG, "ColorOS lyric provider bindings broadcast failed reason=$reason", throwable)
        }
    }

    private fun deliver(context: Context, intent: Intent): Result<Unit> =
        runCatching { context.sendBroadcast(intent) }
}
