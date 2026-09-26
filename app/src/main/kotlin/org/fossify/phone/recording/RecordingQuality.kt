package org.fossify.phone.recording

/** PCM profiles avoid codec CPU cost. Changing the preference affects the next recording only. */
enum class RecordingQuality(
    val preferenceId: Int,
    val sampleRates: List<Int>,
    val readBufferMs: Int,
    val pollIntervalMs: Long
) {
    LIGHT(0, listOf(8_000), 200, 100),
    BALANCED(1, listOf(16_000, 8_000), 160, 80),
    HIGH(2, listOf(48_000, 44_100, 16_000, 8_000), 100, 50);

    fun readBufferSamples(sampleRate: Int): Int = sampleRate * readBufferMs / 1_000

    fun inputBufferBytes(sampleRate: Int, minimum: Int): Int =
        maxOf(minimum * 4, readBufferSamples(sampleRate) * 2 * 4)

    companion object {
        fun fromPreference(value: Int): RecordingQuality = entries.firstOrNull { it.preferenceId == value } ?: BALANCED
    }
}
