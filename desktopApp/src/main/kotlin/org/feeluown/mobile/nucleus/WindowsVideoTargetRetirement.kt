package org.feeluown.mobile.nucleus

/**
 * Keep replaced video textures alive across a few draw passes. A source swap is asynchronous:
 * TextureView can still have an import of the previous shared handle while Compose applies it.
 * Call [onFrame] within Tao's frame callback, where GPU resource release is permitted.
 * This is a draw-frame grace period, not a substitute for GPU/consumer completion fences.
 */
internal class WindowsVideoTargetRetirement<T : AutoCloseable>(
    private val graceFrames: Int = 3,
) : AutoCloseable {
    init {
        require(graceFrames > 0)
    }

    private data class Retired<T>(val value: T, var remainingFrames: Int)

    private val pending = ArrayDeque<Retired<T>>()
    private var closed = false

    fun retire(value: T) {
        check(!closed) { "video target retirement is already closed" }
        check(pending.none { it.value === value }) { "video target was already retired" }
        pending.addLast(Retired(value, graceFrames))
    }

    fun onFrame() {
        if (closed) return
        repeat(pending.size) {
            val entry = pending.removeFirst()
            entry.remainingFrames--
            if (entry.remainingFrames == 0) entry.value.close()
            else pending.addLast(entry)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        // Teardown must release every target even if one native release fails.
        while (pending.isNotEmpty()) {
            try {
                pending.removeFirst().value.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
