package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePolicyTest {
    @Test
    fun newerRemoteBuildIsAvailableOnStable() {
        assertEquals(
            AppUpdateDecision.UpdateAvailable,
            evaluateAppUpdate(
                installedVersionCode = 100,
                channel = AppUpdateChannel.Stable,
                remoteVersionCode = 101,
            ),
        )
    }

    @Test
    fun newerRemoteBuildIsAvailableOnCanary() {
        assertEquals(
            AppUpdateDecision.UpdateAvailable,
            evaluateAppUpdate(
                installedVersionCode = 100,
                channel = AppUpdateChannel.Canary,
                remoteVersionCode = 101,
            ),
        )
    }

    @Test
    fun equalBuildIsUpToDate() {
        assertEquals(
            AppUpdateDecision.UpToDate,
            evaluateAppUpdate(
                installedVersionCode = 100,
                channel = AppUpdateChannel.Stable,
                remoteVersionCode = 100,
            ),
        )
    }

    @Test
    fun stableDoesNotDowngradeNewerInstalledBuild() {
        assertEquals(
            AppUpdateDecision.WaitingForStable,
            evaluateAppUpdate(
                installedVersionCode = 120,
                channel = AppUpdateChannel.Stable,
                remoteVersionCode = 100,
            ),
        )
    }

    @Test
    fun olderCanaryManifestIsIgnored() {
        assertEquals(
            AppUpdateDecision.UpToDate,
            evaluateAppUpdate(
                installedVersionCode = 120,
                channel = AppUpdateChannel.Canary,
                remoteVersionCode = 100,
            ),
        )
    }

    @Test
    fun knownNewerBuildRetriesUpdateOperation() {
        assertTrue(
            shouldRetryKnownAppUpdate(
                installedVersionCode = 100,
                remoteVersionCode = 101,
            ),
        )
    }

    @Test
    fun missingOrNonNewerBuildRetriesUpdateCheck() {
        assertFalse(shouldRetryKnownAppUpdate(installedVersionCode = 100, remoteVersionCode = null))
        assertFalse(shouldRetryKnownAppUpdate(installedVersionCode = 100, remoteVersionCode = 100))
        assertFalse(shouldRetryKnownAppUpdate(installedVersionCode = 100, remoteVersionCode = 99))
    }
}
