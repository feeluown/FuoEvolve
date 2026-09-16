package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsVideoTargetRetirementTest {
    @Test
    fun replacedTargetSurvivesUntilThreeDrawFramesHavePassed() {
        val queue = WindowsVideoTargetRetirement<TrackedTarget>()
        val previous = TrackedTarget()
        queue.retire(previous)

        queue.onFrame()
        queue.onFrame()
        assertEquals(0, previous.closes)

        queue.onFrame()
        assertEquals(1, previous.closes)
        queue.close()
        assertEquals(1, previous.closes)
    }

    @Test
    fun newlyRetiredTargetsReceiveTheirOwnGracePeriod() {
        val queue = WindowsVideoTargetRetirement<TrackedTarget>()
        val first = TrackedTarget()
        val second = TrackedTarget()
        queue.retire(first)
        queue.onFrame()
        queue.retire(second)
        queue.onFrame()
        queue.onFrame()

        assertEquals(1, first.closes)
        assertEquals(0, second.closes)
        queue.onFrame()
        assertEquals(1, second.closes)
    }

    @Test
    fun teardownClosesRemainingTargetsWithoutDoubleRelease() {
        val queue = WindowsVideoTargetRetirement<TrackedTarget>()
        val pending = TrackedTarget()
        queue.retire(pending)
        queue.onFrame()
        queue.close()
        queue.close()
        queue.onFrame()

        assertEquals(1, pending.closes)
        assertFailsWith<IllegalStateException> { queue.retire(TrackedTarget()) }
    }

    @Test
    fun duplicateRetirementIsRejected() {
        val queue = WindowsVideoTargetRetirement<TrackedTarget>()
        val target = TrackedTarget()
        queue.retire(target)
        assertFailsWith<IllegalStateException> { queue.retire(target) }
        queue.close()
        assertEquals(1, target.closes)
    }

    @Test
    fun cleanupReleasesEveryTargetEvenIfOneReleaseFails() {
        val queue = WindowsVideoTargetRetirement<TrackedTarget>()
        val broken = TrackedTarget(failOnClose = true)
        val healthy = TrackedTarget()
        queue.retire(broken)
        queue.retire(healthy)
        assertFailsWith<IllegalStateException> { queue.close() }
        assertEquals(1, broken.closes)
        assertEquals(1, healthy.closes)
        queue.close()
        assertEquals(1, broken.closes)
    }

    @Test
    fun gracePeriodMustBePositive() {
        assertFailsWith<IllegalArgumentException> { WindowsVideoTargetRetirement<TrackedTarget>(0) }
    }

    private class TrackedTarget(private val failOnClose: Boolean = false) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            if (failOnClose) throw IllegalStateException("native release failed")
        }
    }
}
