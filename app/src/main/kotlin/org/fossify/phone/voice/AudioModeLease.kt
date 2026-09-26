package org.fossify.phone.voice

/** Own only our mode request, and restore it even when entry partly succeeds then throws. */
class AudioModeLease(private val enter: () -> Unit, private val withdraw: () -> Unit) : AutoCloseable {
    private var entered = false
    private var closed = false

    @Synchronized
    fun acquire(): Boolean {
        if (closed) return false // Cancellation can win the race with worker startup.
        check(!entered)
        entered = true
        enter()
        return true
    }

    @Synchronized
    override fun close() {
        if (closed && !entered) return
        closed = true // Never permit another acquire, even if withdrawal fails.
        if (entered) {
            withdraw()
            entered = false // A failed withdrawal remains retryable by worker cleanup.
        }
    }
}
