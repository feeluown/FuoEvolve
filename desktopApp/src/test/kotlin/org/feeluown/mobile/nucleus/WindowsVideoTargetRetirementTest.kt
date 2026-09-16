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

        assertEquals(1, pending.closes)
    }

    @Test
    fun gracePeriodMustBePositive() {
        assertFailsWith<IllegalArgumentException> { WindowsVideoTargetRetirement<TrackedTarget>(0) }
    }

    private class TrackedTarget : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }
}
