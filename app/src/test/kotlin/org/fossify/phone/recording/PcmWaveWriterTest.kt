package org.fossify.phone.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `writes correct header and signed little endian PCM`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 16_000)
        writer.write(shortArrayOf(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE), 5)
        writer.close()
        val data = file.readBytes()
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(54, data.size)
        assertEquals("RIFF", String(data, 0, 4, Charsets.US_ASCII))
        assertEquals(46, header.getInt(4))
        assertEquals("WAVEfmt ", String(data, 8, 8, Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20))
        assertEquals(1.toShort(), header.getShort(22))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2.toShort(), header.getShort(32))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals("data", String(data, 36, 4, Charsets.US_ASCII))
        assertEquals(10, header.getInt(40))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, 0, -128, -1, 127), data.copyOfRange(44, data.size))
        assertTrue(writer.hasAudio)
    }

    @Test
    fun `zero samples do not count as audio regardless of file size`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 16_000)
        writer.use { repeat(50) { writer.write(ShortArray(1600), 1600) } }
        assertTrue(file.length() > 44)
        assertFalse(writer.hasAudio)
    }

    @Test
    fun `only successfully written prefix contributes signal evidence`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 8_000)
        writer.write(shortArrayOf(0, 0, 100), 2)
        assertFalse(writer.hasAudio)
        writer.write(shortArrayOf(-1), 1)
        assertTrue(writer.hasAudio)
        writer.close()
        assertEquals(50L, file.length())
    }

    @Test
    fun `empty recording finalizes without claiming audio`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 48_000)
        writer.close()
        assertFalse(writer.hasAudio)
        assertEquals(44L, file.length())
        assertEquals(0, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test
    fun `close is idempotent and accumulates writes`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 44_100)
        repeat(3) { writer.write(shortArrayOf(1, 2), 2) }
        writer.close()
        val first = file.readBytes()
        writer.close()
        assertArrayEquals(first, file.readBytes())
        assertEquals(56, first.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `cannot write after finalization`() {
        val writer = PcmWaveWriter(temporary.newFile(), 16_000)
        writer.close()
        writer.write(shortArrayOf(1), 1)
    }

    @Test
    fun `rejects invalid RIFF sizes instead of integer overflow`() {
        for (size in longArrayOf(-1, 1, 0x1_0000_0000L)) {
            try {
                PcmWaveWriter.header(16_000, size)
                throw AssertionError("Should reject $size")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `capture failure leaves earlier PCM finalizable`() {
        val file = temporary.newFile()
        val writer = PcmWaveWriter(file, 16_000)
        var reads = 0
        val input = object : PcmInput {
            override val sampleRate = 16_000
            override fun isSilenced() = false
            override fun read(samples: ShortArray): Int {
                samples[0] = 42
                return if (++reads == 1) 1 else -6
            }
        }
        try {
            writer.use { PcmCaptureLoop(input, { false }, { false }, writer::write, idle = {}).run() }
            throw AssertionError("Should fail on dead input")
        } catch (_: CaptureException) {
        }
        assertTrue(writer.hasAudio)
        assertEquals(46L, file.length())
        assertEquals(2, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }
}
