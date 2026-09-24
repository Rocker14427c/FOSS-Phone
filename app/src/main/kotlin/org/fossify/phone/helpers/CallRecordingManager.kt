package org.fossify.phone.helpers

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages a single recorder during a call. Privileged installs can use VOICE_CALL for
 * uplink and downlink audio. If CAPTURE_AUDIO_OUTPUT is not granted (or the device rejects
 * that source), recording falls back to the microphone and may not include the other caller.
 */
object CallRecordingManager {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var capturesCallAudio = false

    @Synchronized
    fun isRecording(): Boolean = recorder != null

    @Synchronized
    fun isCapturingCallAudio(): Boolean = recorder != null && capturesCallAudio

    @Synchronized
    fun start(context: Context): Boolean {
        if (recorder != null) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val canCaptureCallAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAPTURE_AUDIO_OUTPUT
        ) == PackageManager.PERMISSION_GRANTED
        return startWithSource(context, canCaptureCallAudio)
    }

    private fun startWithSource(context: Context, useCallAudioSource: Boolean): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var newRecorder: MediaRecorder? = null
        try {
            val outputTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Call_$timestamp.m4a")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Fossify Phone")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                outputUri = uri
                val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                if (descriptor == null) {
                    cleanupOutput(context, delete = true)
                    return false
                }
                outputDescriptor = descriptor
                OutputTarget(descriptor.fileDescriptor, null)
            } else {
                val musicDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return false
                val directory = File(musicDirectory, "CallRecordings")
                if (!directory.exists() && !directory.mkdirs()) return false
                val file = File(directory, "Call_$timestamp.m4a")
                outputFile = file
                OutputTarget(null, file)
            }

            newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.setAudioSource(
                if (useCallAudioSource) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.MIC
            )
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(128_000)
            newRecorder.setAudioSamplingRate(44_100)
            if (outputTarget.fileDescriptor != null) {
                newRecorder.setOutputFile(outputTarget.fileDescriptor)
            } else {
                newRecorder.setOutputFile(outputTarget.file!!.absolutePath)
            }
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            capturesCallAudio = useCallAudioSource
            return true
        } catch (_: Exception) {
            try {
                newRecorder?.release()
            } catch (_: Exception) {
            }
            cleanupOutput(context, delete = true)
            recorder = null
            capturesCallAudio = false
            return if (useCallAudioSource) startWithSource(context, useCallAudioSource = false) else false
        }
    }

    @Synchronized
    fun stop(context: Context): Boolean {
        val currentRecorder = recorder ?: return false
        recorder = null
        capturesCallAudio = false
        var validRecording = true
        try {
            currentRecorder.stop()
        } catch (_: RuntimeException) {
            // MediaRecorder.stop() can fail if no valid audio frame was captured.
            validRecording = false
        } finally {
            try {
                currentRecorder.reset()
                currentRecorder.release()
            } catch (_: Exception) {
            }
            cleanupOutput(context, delete = !validRecording)
        }
        return validRecording
    }

    private fun cleanupOutput(context: Context, delete: Boolean) {
        outputDescriptor?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        outputDescriptor = null
        outputUri?.let { uri ->
            try {
                if (delete) {
                    context.contentResolver.delete(uri, null, null)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(uri, values, null, null)
                }
            } catch (_: Exception) {
            }
        }
        outputUri = null
        outputFile?.let {
            try {
                if (delete) it.delete()
            } catch (_: Exception) {
            }
        }
        outputFile = null
    }

    private data class OutputTarget(val fileDescriptor: java.io.FileDescriptor?, val file: File?)
}
