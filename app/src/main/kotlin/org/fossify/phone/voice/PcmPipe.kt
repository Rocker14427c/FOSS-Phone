package org.fossify.phone.voice

/** One bounded, non-blocking PCM direction. Partial writes must never lose/reprocess samples. */
class PcmPipe(
    frameSamples: Int,
    private val read: (ShortArray) -> Int,
    private val write: (ShortArray, Int, Int) -> Int,
    private val transform: (ShortArray, Int) -> Unit = { _, _ -> },
    startedAtMs: Long,
    private val stallTimeoutMs: Long = 1_000,
) {
    init { require(frameSamples > 0 && stallTimeoutMs > 0) }
    private val samples = ShortArray(frameSamples)
    private var offset = 0
    private var count = 0
    private var lastProgressMs = startedAtMs

    fun step(nowMs: Long) {
        if (offset == count) {
            offset = 0
            count = read(samples)
            check(count in 0..samples.size) { "Call audio input failed ($count)" }
            if (count > 0) {
                transform(samples, count)
                lastProgressMs = nowMs
            }
        }
        if (offset < count) {
            val written = write(samples, offset, count - offset)
            check(written in 0..count - offset) { "Call audio output failed ($written)" }
            if (written > 0) {
                offset += written
                lastProgressMs = nowMs
            }
        }
        check(nowMs - lastProgressMs < stallTimeoutMs) { "Call audio stalled" }
    }
}
