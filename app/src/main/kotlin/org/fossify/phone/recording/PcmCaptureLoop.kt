package org.fossify.phone.recording

/** Implementations must perform NONBLOCKING reads; cancellation must never wait for the next call. */
internal interface PcmInput {
    val sampleRate: Int
    fun read(samples: ShortArray): Int
    fun isSilenced(): Boolean
}

internal class CaptureException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { READ_FAILED, BLOCKED, NO_DATA }
}

/** Android-independent read loop, so cancellation, hold and failures can be regression tested. */
internal class PcmCaptureLoop(
    private val input: PcmInput,
    private val cancelled: () -> Boolean,
    private val holding: () -> Boolean,
    private val write: (ShortArray, Int) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idle: () -> Unit = { Thread.sleep(20) },
    private val readBufferMs: Int = 100
) {
    fun run() {
        require(readBufferMs in 50..250)
        val samples = ShortArray(input.sampleRate * readBufferMs / 1_000)
        var lastData = nowMs()
        var silencedSince: Long? = null
        var lastPolicyCheck = lastData
        var silenced = false
        while (!cancelled()) {
            val count = input.read(samples)
            if (count < 0 || count > samples.size) throw CaptureException(CaptureException.Reason.READ_FAILED)
            val now = nowMs()
            val onHold = holding()
            if (count > 0 || onHold) lastData = now
            if (count > 0 && !onHold) write(samples, count)

            if (now - lastPolicyCheck >= 1_000) {
                silenced = input.isSilenced()
                lastPolicyCheck = now
            }
            // A normal quiet pause returns zero-valued samples, not zero frames. Do not confuse
            // that with policy blocking or a dead input. Continue draining the input while held.
            if (onHold || !silenced) {
                silencedSince = null
            } else {
                val since = silencedSince ?: now.also { silencedSince = it }
                if (now - since >= 3_000) throw CaptureException(CaptureException.Reason.BLOCKED)
            }
            if (!onHold && now - lastData >= 10_000) throw CaptureException(CaptureException.Reason.NO_DATA)
            if (!cancelled()) idle()
        }
    }
}
