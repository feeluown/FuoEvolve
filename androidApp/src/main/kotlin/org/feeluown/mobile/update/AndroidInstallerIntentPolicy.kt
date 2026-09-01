package org.feeluown.mobile

internal enum class AndroidInstallerIntentAction {
    InstallPackage,
    ViewPackage,
}

internal fun androidInstallerIntentActions(apiLevel: Int): List<AndroidInstallerIntentAction> =
    if (apiLevel <= ANDROID_P_API_LEVEL) {
        listOf(
            AndroidInstallerIntentAction.InstallPackage,
            AndroidInstallerIntentAction.ViewPackage,
        )
    } else {
        listOf(
            AndroidInstallerIntentAction.ViewPackage,
            AndroidInstallerIntentAction.InstallPackage,
        )
    }

private const val ANDROID_P_API_LEVEL = 28
