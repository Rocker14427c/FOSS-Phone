package org.fossify.phone.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PcmCaptureLoopTest {
    private class Input(private val reader: (ShortArray, Int) -> Int) : PcmInput {
        override val sampleRate = 16_000
        var reads = 0
        var silenced = false
        override fun read(samples: ShortArray): Int = reader(samples, ++reads)
        override fun isSilenced() = silenced
    }

    @Test
    fun `cancel before start does not read input`() {
        val input = Input { _, _ -> error("must not read") }
        PcmCaptureLoop(input, { true }, { false }, { _, _ -> error("must not write") }).run()
        assertEquals(0, input.reads)
    }

    @Test
    fun `writes only samples actually read including short buffers`() {
        val input = Input { samples, _ -> samples[0] = 1; samples[1] = -2; 2 }
        var written = shortArrayOf()
        PcmCaptureLoop(input, { input.reads == 1 }, { false }, { data, count -> written = data.copyOf(count) }).run()
        assertArrayEquals(shortArrayOf(1, -2), written)
    }

    @Test
    fun `hold drains input without saving held audio and resumes`() {
        val input = Input { samples, n -> samples[0] = n.toShort(); 1 }
        val written = mutableListOf<Short>()
        PcmCaptureLoop(input, { input.reads == 6 }, { input.reads in 2..4 }, { data, _ -> written.add(data[0]) }, idle = {}).run()
        assertEquals(listOf<Short>(1, 5, 6), written)
        assertEquals(6, input.reads)
    }

    @Test
    fun `no-data source remains cancellable without another call`() {
        val input = Input { _, _ -> 0 }
        var time = 0L
        PcmCaptureLoop(input, { time >= 100 }, { false }, { _, _ -> error("must not write") }, { time }, { time += 20 }).run()
        assertEquals(5, input.reads)
    }

    @Test
    fun `stalled input fails instead of spinning forever`() {
        val input = Input { _, _ -> 0 }
        var time = 0L
        val loop = PcmCaptureLoop(input, { false }, { false }, { _, _ -> }, { time }, { time += 20 })
        assertFailure(CaptureException.Reason.NO_DATA, loop)
        assertEquals(10_000L, time)
    }

    @Test
    fun `ordinary quiet samples are not a stalled or blocked input`() {
        val input = Input { samples, _ -> samples[0] = 0; 1 }
        var time = 0L
        var written = 0
        PcmCaptureLoop(input, { time >= 12_000 }, { false }, { _, count -> written += count }, { time }, { time += 20 }).run()
        assertEquals(600, written)
    }

    @Test
    fun `persistent policy silencing reports blocked`() {
        val input = Input { _, _ -> 1 }.apply { silenced = true }
        var time = 0L
        assertFailure(
            CaptureException.Reason.BLOCKED,
            PcmCaptureLoop(input, { false }, { false }, { _, _ -> }, { time }, { time += 20 })
        )
        assertEquals(4_000L, time)
    }

    @Test
    fun `hold resets policy and missing-data grace periods`() {
        val input = Input { _, _ -> 0 }.apply { silenced = true }
        var time = 0L
        PcmCaptureLoop(
            input, { time >= 20_000 }, { true }, { _, _ -> error("must not write") }, { time }, { time += 20 }
        ).run()
        assertTrue(input.reads > 0)
    }

    @Test
    fun `transient policy silencing can recover`() {
        var time = 0L
        val input = Input { _, _ -> 1 }
        PcmCaptureLoop(
            input, { time >= 6_000 }, { false }, { _, _ -> }, { time },
            { time += 20; input.silenced = time < 2_000 }
        ).run()
        assertEquals(6_000L, time)
    }

    @Test
    fun `negative and invalid read sizes fail immediately`() {
        for (count in intArrayOf(-6, -3, -2, -1, 1601)) {
            val input = Input { _, _ -> count }
            assertFailure(CaptureException.Reason.READ_FAILED, PcmCaptureLoop(input, { false }, { false }, { _, _ -> }))
            assertEquals(1, input.reads)
        }
    }

    @Test(expected = IOException::class)
    fun `storage error escapes capture loop for finalization`() {
        val input = Input { _, _ -> 1 }
        PcmCaptureLoop(input, { false }, { false }, { _, _ -> throw IOException("disk full") }).run()
    }

    private fun assertFailure(reason: CaptureException.Reason, loop: PcmCaptureLoop) {
        try {
            loop.run()
            throw AssertionError("Expected $reason")
        } catch (error: CaptureException) {
            assertEquals(reason, error.reason)
        }
    }
}
