package org.fossify.phone.recording

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono PCM16 WAV writer. Finalizes sizes before the caller publishes the temporary file. */
internal class PcmWaveWriter(file: File, private val sampleRate: Int, initialBufferSamples: Int = 0) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var closed = false
    private var bytesWritten = 0L
    private var buffer = ByteArray(initialBufferSamples * 2)
    var hasAudio = false
        private set

    init {
        try {
            require(sampleRate in 8_000..48_000)
            output.setLength(0)
            output.write(header(sampleRate, 0))
        } catch (error: Exception) {
            output.close()
            throw error
        }
    }

    fun write(samples: ShortArray, count: Int) {
        check(!closed)
        require(count in 0..samples.size)
        val size = count * 2
        if (bytesWritten + size > MAX_DATA_SIZE) throw IOException("WAV size limit reached")
        if (buffer.size < size) buffer = ByteArray(size)
        var nonzero = false
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            nonzero = nonzero || sample != 0
            buffer[i * 2] = sample.toByte()
            buffer[i * 2 + 1] = (sample shr 8).toByte()
        }
        output.write(buffer, 0, size)
        bytesWritten += size
        // Only count samples that were successfully written, never hold/discarded audio.
        hasAudio = hasAudio || nonzero
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // A failed write may have written only part of a buffer. Drop that partial buffer.
            output.setLength(HEADER_SIZE + bytesWritten)
            output.seek(0)
            output.write(header(sampleRate, bytesWritten))
            output.fd.sync()
        } finally {
            output.close()
        }
    }

    companion object {
        private const val HEADER_SIZE = 44L
        private const val MAX_DATA_SIZE = 0xffff_fffeL - 36L

        internal fun header(sampleRate: Int, dataSize: Long): ByteArray {
            require(dataSize in 0..MAX_DATA_SIZE && dataSize % 2 == 0L)
            return ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataSize).toInt())
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // mono
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2) // block alignment
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize.toInt())
            }.array()
        }
    }
}
