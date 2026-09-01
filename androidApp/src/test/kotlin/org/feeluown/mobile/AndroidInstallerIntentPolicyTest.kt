package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidInstallerIntentPolicyTest {
    @Test
    fun androidSevenPrefersInstallPackageAction() {
        assertEquals(
            listOf(
                AndroidInstallerIntentAction.InstallPackage,
                AndroidInstallerIntentAction.ViewPackage,
            ),
            androidInstallerIntentActions(apiLevel = 24),
        )
    }

    @Test
    fun androidNineStillPrefersInstallPackageAction() {
        assertEquals(
            listOf(
                AndroidInstallerIntentAction.InstallPackage,
                AndroidInstallerIntentAction.ViewPackage,
            ),
            androidInstallerIntentActions(apiLevel = 28),
        )
    }

    @Test
    fun androidTenAndNewerPreferViewAction() {
        assertEquals(
            listOf(
                AndroidInstallerIntentAction.ViewPackage,
                AndroidInstallerIntentAction.InstallPackage,
            ),
            androidInstallerIntentActions(apiLevel = 29),
        )
        assertEquals(
            listOf(
                AndroidInstallerIntentAction.ViewPackage,
                AndroidInstallerIntentAction.InstallPackage,
            ),
            androidInstallerIntentActions(apiLevel = 35),
        )
    }
}
