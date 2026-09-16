package org.feeluown.mobile.nucleus

/**
 * Keep replaced video textures alive across a few draw passes. A source swap is asynchronous:
 * TextureView can still have an import of the previous shared handle while Compose applies it.
 * Call [onFrame] within Tao's frame callback, where GPU resource release is permitted.
 * This is a draw-frame grace period, not a substitute for a GPU completion fence.
 */
internal class WindowsVideoTargetRetirement<T : AutoCloseable>(
    private val graceFrames: Int = 3,
) : AutoCloseable {
    init {
        require(graceFrames > 0)
    }

    private data class Retired<T>(val value: T, var remainingFrames: Int)

    private val pending = ArrayDeque<Retired<T>>()

    fun retire(value: T) {
        pending.addLast(Retired(value, graceFrames))
    }

    fun onFrame() {
        repeat(pending.size) {
            val entry = pending.removeFirst()
            entry.remainingFrames--
            if (entry.remainingFrames == 0) entry.value.close()
            else pending.addLast(entry)
        }
    }

    override fun close() {
        while (pending.isNotEmpty()) pending.removeFirst().value.close()
    }
}
