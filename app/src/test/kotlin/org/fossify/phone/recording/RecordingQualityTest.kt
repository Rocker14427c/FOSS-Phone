package org.fossify.phone.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingQualityTest {
    @Test
    fun `saved choices are stable and unknown values are safe`() {
        assertEquals(RecordingQuality.LIGHT, RecordingQuality.fromPreference(0))
        assertEquals(RecordingQuality.BALANCED, RecordingQuality.fromPreference(1))
        assertEquals(RecordingQuality.HIGH, RecordingQuality.fromPreference(2))
        assertEquals(RecordingQuality.BALANCED, RecordingQuality.fromPreference(999))
    }

    @Test
    fun `fallback never exceeds the selected quality budget`() {
        for (quality in RecordingQuality.entries) {
            assertTrue(quality.sampleRates.zipWithNext().all { (a, b) -> a > b })
            assertTrue(quality.sampleRates.all { it <= quality.sampleRates.first() })
        }
        assertEquals(listOf(8_000), RecordingQuality.LIGHT.sampleRates)
    }

    @Test
    fun `light mode polls less often and uses less data`() {
        assertTrue(RecordingQuality.LIGHT.pollIntervalMs > RecordingQuality.BALANCED.pollIntervalMs)
        assertTrue(RecordingQuality.BALANCED.pollIntervalMs > RecordingQuality.HIGH.pollIntervalMs)
        assertEquals(960_000, RecordingQuality.LIGHT.sampleRates.first() * 2 * 60)
        assertEquals(1_920_000, RecordingQuality.BALANCED.sampleRates.first() * 2 * 60)
        assertEquals(5_760_000, RecordingQuality.HIGH.sampleRates.first() * 2 * 60)
    }

    @Test
    fun `buffers cover several poll intervals and stay bounded`() {
        for (quality in RecordingQuality.entries) {
            for (rate in quality.sampleRates) {
                val inputBytes = quality.inputBufferBytes(rate, 1024)
                val bytesPerPoll = rate * 2 * quality.pollIntervalMs / 1000
                assertTrue(inputBytes >= 4 * bytesPerPoll)
                assertTrue(inputBytes < 64 * 1024)
                assertTrue(quality.readBufferSamples(rate) >= rate * quality.pollIntervalMs / 1000)
            }
        }
    }

    @Test
    fun `each profile captures exact sample counts without encoding work`() {
        for (quality in RecordingQuality.entries) {
            var reads = 0
            var written = 0
            val input = object : PcmInput {
                override val sampleRate = quality.sampleRates.first()
                override fun isSilenced() = false
                override fun read(samples: ShortArray): Int {
                    assertEquals(quality.readBufferSamples(sampleRate), samples.size)
                    reads++
                    samples.fill(1)
                    return samples.size
                }
            }
            PcmCaptureLoop(
                input, { reads == 3 }, { false }, { _, count -> written += count },
                idle = {}, readBufferMs = quality.readBufferMs
            ).run()
            assertEquals(3 * quality.readBufferSamples(input.sampleRate), written)
        }
    }
}
