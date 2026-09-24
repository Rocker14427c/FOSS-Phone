package org.fossify.phone.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages a single recorder for the whole call session, including hold, swap and multiple calls.
 *
 * Privileged installs holding CAPTURE_AUDIO_OUTPUT get true two-way audio via the VOICE_CALL
 * source. Otherwise recording falls back to the microphone and the other caller may not be
 * audible. Recordings are kept in app-private storage (visible only to this app), written to an
 * ".inprogress" temp name and renamed only after a valid recording was captured, so a crash can
 * never leave a broken recording behind.
 */
object CallRecordingManager {
    private const val RECORDINGS_DIR = "Recordings"
    private const val EXTENSION = ".m4a"
    private const val IN_PROGRESS_SUFFIX = ".inprogress"
    private const val BEEP_DURATION_MS = 300
    private const val MAX_LABEL_LENGTH = 32

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var inProgressFile: File? = null
    private var capturesCallAudio = false
    private var userStoppedThisSession = false

    @Synchronized
    fun isRecording(): Boolean = recorder != null

    @Synchronized
    fun isCapturingCallAudio(): Boolean = recorder != null && capturesCallAudio

    fun isFeatureEnabled(context: Context): Boolean {
        return context.resources.getBoolean(R.bool.show_call_recording)
    }

    fun getRecordingsDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, RECORDINGS_DIR)
    }

    /**
     * @param automatic true when triggered by the auto-record setting. Automatic starts are
     * suppressed for the rest of the call session once the user manually stopped recording.
     * @param label optional caller hint (e.g. the phone number) added to the file name.
     */
    @Synchronized
    fun start(context: Context, automatic: Boolean = false, label: String? = null): Boolean {
        if (!isFeatureEnabled(context)) return false
        if (recorder != null) return true
        if (automatic && userStoppedThisSession) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        cleanupIncompleteRecordings(context)

        val canCaptureCallAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAPTURE_AUDIO_OUTPUT
        ) == PackageManager.PERMISSION_GRANTED

        val started = startWithSource(context, canCaptureCallAudio, label)
        if (started) {
            userStoppedThisSession = false
            if (context.config.playBeepWhenRecording) {
                playStartBeep()
            }
        }
        notifyStateChanged()
        return started
    }

    private fun startWithSource(context: Context, useCallAudioSource: Boolean, label: String?): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedLabel = sanitizeLabel(label)
        val nameSuffix = if (sanitizedLabel.isEmpty()) "" else "_$sanitizedLabel"
        var newRecorder: MediaRecorder? = null
        try {
            val directory = getRecordingsDirectory(context)
            if (!directory.exists() && !directory.mkdirs()) return false
            val target = File(directory, "Call_$timestamp$nameSuffix$EXTENSION")
            val temp = File(directory, "${target.name}$IN_PROGRESS_SUFFIX")
            outputFile = target
            inProgressFile = temp

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
            newRecorder.setOutputFile(temp.absolutePath)
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
            discardFiles()
            recorder = null
            capturesCallAudio = false
            return if (useCallAudioSource) startWithSource(context, useCallAudioSource = false, label) else false
        }
    }

    /**
     * @param stoppedByUser true when the user pressed stop. Automatic restarts stay disabled for
     * the rest of the call session in that case.
     * @return true if a valid recording was saved, false if there was nothing to save or the
     * empty recording was discarded.
     */
    @Synchronized
    fun stop(context: Context, stoppedByUser: Boolean = false): Boolean {
        if (stoppedByUser) {
            userStoppedThisSession = true
        }
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
            if (validRecording) {
                saveFile()
            } else {
                discardFiles()
            }
        }
        notifyStateChanged()
        return validRecording
    }

    /** Stops any active recording (saving it) and resets the per-session state. */
    @Synchronized
    fun endSession(context: Context) {
        stop(context, stoppedByUser = false)
        userStoppedThisSession = false
    }

    @Synchronized
    fun getRecordings(context: Context): List<File> {
        cleanupIncompleteRecordings(context)
        return getRecordingsDirectory(context).listFiles { file ->
            file.isFile && file.name.endsWith(EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteRecording(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Removes leftovers of recordings interrupted by a crash or process death. */
    @Synchronized
    fun cleanupIncompleteRecordings(context: Context) {
        val currentTempPath = inProgressFile?.absolutePath
        getRecordingsDirectory(context).listFiles { file ->
            file.isFile && file.name.endsWith(IN_PROGRESS_SUFFIX) && file.absolutePath != currentTempPath
        }?.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveFile() {
        val temp = inProgressFile
        val target = outputFile
        if (temp == null || target == null) {
            discardFiles()
            return
        }
        try {
            if (temp.exists() && temp.length() > 0 && temp.renameTo(target)) {
                inProgressFile = null
                outputFile = null
            } else {
                discardFiles()
            }
        } catch (_: Exception) {
            discardFiles()
        }
    }

    private fun discardFiles() {
        inProgressFile?.let {
            try {
                it.delete()
            } catch (_: Exception) {
            }
        }
        inProgressFile = null
        outputFile = null
    }

    private fun sanitizeLabel(label: String?): String {
        val sanitized = label.orEmpty().map { character ->
            if (character.isLetterOrDigit() || character == '+' || character == '-' || character == '_') {
                character
            } else {
                '_'
            }
        }.joinToString("").trim('_')
        return sanitized.take(MAX_LABEL_LENGTH)
    }

    /** Plays a short beep on the call audio path so the other party hears that recording started. */
    private fun playStartBeep() {
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
        } catch (_: Exception) {
            tone = null
        }

        val startedTone = tone ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                startedTone.release()
            } catch (_: Exception) {
            }
        }, BEEP_DURATION_MS + 200L)
    }

    private fun notifyStateChanged() {
        EventBus.getDefault().post(Events.RecordingStateChanged)
    }
}
