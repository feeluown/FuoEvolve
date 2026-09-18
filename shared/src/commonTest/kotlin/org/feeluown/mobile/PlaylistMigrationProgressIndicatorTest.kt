package org.feeluown.mobile

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistMigrationProgressIndicatorTest {
    @Test
    fun actualProgressAlwaysUsesCompletedCheckpoints() {
        assertEquals(0f, migrationActualFraction(0, 0))
        assertEquals(0f, migrationActualFraction(-1, 10))
        assertTrue(abs(migrationActualFraction(3, 10) - 0.3f) < 0.00001f)
        assertEquals(1f, migrationActualFraction(12, 10))
    }

    @Test
    fun estimatedProgressNeverClaimsTheNextSongIsFinished() {
        assertEquals(0f, migrationInFlightFraction(0, 0))
        assertTrue(abs(migrationInFlightFraction(0, 1) - 0.9f) < 0.00001f)
        assertTrue(abs(migrationInFlightFraction(3, 10) - 0.39f) < 0.00001f)
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
