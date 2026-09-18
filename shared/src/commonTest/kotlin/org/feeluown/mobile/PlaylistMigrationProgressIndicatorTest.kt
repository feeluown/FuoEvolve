package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistMigrationProgressIndicatorTest {
    @Test
    fun actualProgressAlwaysUsesCompletedCheckpoints() {
        assertEquals(0f, migrationActualFraction(0, 0))
        assertEquals(0f, migrationActualFraction(-1, 10))
        assertEquals(0.3f, migrationActualFraction(3, 10))
        assertEquals(1f, migrationActualFraction(12, 10))
    }

    @Test
    fun estimatedProgressNeverClaimsTheNextSongIsFinished() {
        assertEquals(0f, migrationInFlightFraction(0, 0))
        assertEquals(0.9f, migrationInFlightFraction(0, 1))
        assertEquals(0.39f, migrationInFlightFraction(3, 10))
        assertTrue(migrationInFlightFraction(9, 10) < 1f)
        assertEquals(1f, migrationInFlightFraction(10, 10))
    }

    @Test
    fun displayedTargetsNeverRegressWithinAStage() {
        val total = 50
        for (done in 0 until total) {
            val checkpoint = migrationActualFraction(done, total)
            val inFlight = migrationInFlightFraction(done, total)
            val nextCheckpoint = migrationActualFraction(done + 1, total)
            assertTrue(checkpoint <= inFlight)
            assertTrue(inFlight < nextCheckpoint)
        }
    }
}
