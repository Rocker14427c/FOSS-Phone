package org.fossify.phone.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integrated call-audio backend, inspired by BCR's AudioRecord/nonblocking-PCM architecture.
 * No microphone fallback, external recorder app, shell audio capture, or main-thread file I/O.
 */
internal class CallAudioRecorder(
    private val temporary: File,
    private val target: File,
    private val quality: RecordingQuality,
    private val onStarted: () -> Unit,
    private val onCompleted: (Result) -> Unit
) : Thread("CallAudioRecorder") {
    data class Result(val file: File? = null, val failure: Failure? = null)
    enum class Failure { FOREGROUND_UNAVAILABLE, SOURCE_UNAVAILABLE, BLOCKED, CAPTURE_FAILED, NO_AUDIO, STORAGE }

    private val cancelled = AtomicBoolean(false)
    @Volatile var holding = false

    fun cancel() {
        // Never call AudioRecord.stop()/release() from the UI thread while read() is in progress.
        cancelled.set(true)
    }

    override fun run() {
        var input: AndroidInput? = null
        var writer: PcmWaveWriter? = null
        var failure: Failure? = null
        var started = false
        var canPublish = true
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (!cancelled.get()) {
                val directory = temporary.parentFile ?: throw IOException("No recording directory")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create recording directory")
                input = openInput()
                writer = PcmWaveWriter(temporary, input.sampleRate, quality.readBufferSamples(input.sampleRate))
                input.start()
                started = true
                if (!cancelled.get()) onStarted()
                PcmCaptureLoop(
                    input, cancelled::get, { holding }, writer::write,
                    idle = { sleep(quality.pollIntervalMs) }, readBufferMs = quality.readBufferMs
                ).run()
            }
        } catch (error: CaptureException) {
            failure = if (error.reason == CaptureException.Reason.BLOCKED) Failure.BLOCKED else Failure.CAPTURE_FAILED
            Log.w(TAG, "Call capture stopped: ${error.reason}")
        } catch (_: IOException) {
            failure = Failure.STORAGE
        } catch (error: Exception) {
            failure = if (started) Failure.CAPTURE_FAILED else if (!cancelled.get()) Failure.SOURCE_UNAVAILABLE else null
            // Never log filenames/numbers or raw audio.
            Log.w(TAG, "Call capture failed (${error.javaClass.simpleName})")
        } finally {
            try {
                input?.close()
            } catch (_: RuntimeException) {
                if (failure == null) failure = Failure.CAPTURE_FAILED
            }
            try {
                writer?.close()
            } catch (_: Exception) {
                failure = Failure.STORAGE
                canPublish = false
            }
        }

        var saved: File? = null
        try {
            if (canPublish && writer?.hasAudio == true) {
                // Publish only a finalized file. UUID names prevent overwriting earlier recordings.
                if (!target.exists() && temporary.renameTo(target)) {
                    saved = target
                } else {
                    failure = Failure.STORAGE
                }
            } else if (started && failure == null) {
                failure = Failure.NO_AUDIO
            }
            if (saved == null) temporary.delete()
        } catch (_: SecurityException) {
            failure = Failure.STORAGE
        }
        onCompleted(Result(saved, failure))
    }

    @SuppressLint("MissingPermission") // The manager requires both runtime and privileged grants.
    private fun openInput(): AndroidInput {
        // Only retry lower rates within the selected budget. Never silently upgrade Light to a
        // costly input, or switch to MIC/VOICE_COMMUNICATION (which are not two-way call audio).
        for (rate in quality.sampleRates) {
            if (cancelled.get()) throw IllegalStateException("Cancelled before opening input")
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            var record: AudioRecord? = null
            try {
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(quality.inputBufferBytes(rate, minimum))
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) return AndroidInput(record, rate)
            } catch (error: SecurityException) {
                record?.release()
                throw error
            } catch (_: IllegalArgumentException) {
                // Try another sample rate that this HAL accepts.
            } catch (_: UnsupportedOperationException) {
                // AudioRecord.Builder reports unsupported configurations this way on some ROMs.
            }
            record?.release()
        }
        throw IllegalStateException("VOICE_CALL PCM input unavailable")
    }

    private class AndroidInput(private val record: AudioRecord, override val sampleRate: Int) : PcmInput {
        @SuppressLint("MissingPermission") // Checked by the manager; runtime revocation is caught by the worker.
        fun start() {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        }

        override fun read(samples: ShortArray): Int =
            record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)

        override fun isSilenced(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && record.activeRecordingConfiguration?.isClientSilenced == true

        fun close() {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: IllegalStateException) {
            } finally {
                record.release()
            }
        }
    }

    companion object {
        private const val TAG = "CallAudioRecorder"
    }
}
