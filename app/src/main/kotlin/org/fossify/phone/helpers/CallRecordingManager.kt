package org.fossify.phone.helpers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.toast
import org.fossify.phone.voice.VoiceChangerManager
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.models.Events
import org.fossify.phone.recording.CallAudioRecorder
import org.fossify.phone.recording.RecordingQuality
import org.fossify.phone.services.CallService
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Coordinates one mixed call stream per call session. All capture and finalization is asynchronous. */
object CallRecordingManager {
    enum class State { IDLE, STARTING, RECORDING, STOPPING }

    private class Session(val id: String, val label: String?, val generation: Long, val quality: RecordingQuality) {
        var state = State.STARTING
        var worker: CallAudioRecorder? = null
        var service: CallService? = null
        var holding = false
    }

    private data class PendingStart(val context: Context, val label: String?, val generation: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var generation = 0L
    private var userStoppedThisSession = false
    private var automaticFailureReported = false
    private var pendingStart: PendingStart? = null

    @Synchronized
    fun state(): State = session?.state ?: State.IDLE

    @Synchronized
    fun isRecording(): Boolean = session != null

    fun isFeatureEnabled(context: Context): Boolean = context.resources.getBoolean(R.bool.show_call_recording)

    fun hasCallAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED

    fun getRecordingsDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings")

    /** A request is not success: the worker reports RECORDING only after the input actually starts. */
    @Synchronized
    fun start(context: Context, automatic: Boolean = false, label: String? = null) {
        if (!isFeatureEnabled(context)) return
        if (VoiceChangerManager.isBusy()) {
            if (!automatic) context.toast(R.string.voice_trial_recording_conflict)
            return
        }
        if (automatic && (userStoppedThisSession || automaticFailureReported)) return
        if (!CallManager.hasActiveCall()) return
        val current = session
        if (current != null) {
            // A new call may become active while the previous file is still being finalized.
            if (automatic && current.state == State.STOPPING && current.generation != generation) {
                pendingStart = PendingStart(context.applicationContext, label, generation)
            }
            return
        }
        val failure = when {
            !hasCallAudioPermission(context) -> R.string.call_recording_privileged_required
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                R.string.recording_permission_required
            else -> null
        }
        if (failure != null) {
            if (automatic) automaticFailureReported = true
            context.toast(failure)
            return
        }
        userStoppedThisSession = false
        automaticFailureReported = false
        val requested = Session(UUID.randomUUID().toString(), label, generation, context.config.recordingQuality)
        session = requested
        notifyStateChanged()
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, CallService::class.java)
                    .putExtra(CallService.EXTRA_SESSION_ID, requested.id)
            )
        } catch (error: RuntimeException) {
            Log.w("CallRecordingManager", "Cannot launch recording service (${error.javaClass.simpleName})")
            serviceStartFailed(context, requested.id)
        }
    }

    /** Match a per-request nonce before accepting start intents on the Telecom service. */
    @Synchronized
    fun isExpectedServiceRequest(id: String): Boolean = session?.id == id

    /** Called by the permission-protected existing InCallService, not a second recorder service. */
    @Synchronized
    fun serviceReady(service: CallService, id: String) {
        val current = session
        if (current == null || current.id != id) {
            service.finishRecording(id)
            return
        }
        current.service = service
        if (current.state == State.STOPPING) {
            complete(service.applicationContext, current, CallAudioRecorder.Result())
            return
        }
        if (current.worker != null) return
        val directory = getRecordingsDirectory(service)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val label = current.label.orEmpty().map { if (it.isLetterOrDigit() || it in "+-_") it else '_' }
            .joinToString("").trim('_').take(32)
        val target = File(directory, "Call_${timestamp}_${label}_${current.id}.wav")
        val temporary = File(directory, "${target.name}.inprogress")
        val appContext = service.applicationContext
        val worker = CallAudioRecorder(
            temporary, target, current.quality,
            onStarted = { handler.post { started(appContext, current) } },
            onCompleted = { result -> handler.post { complete(appContext, current, result) } }
        )
        worker.holding = current.holding
        current.worker = worker
        try {
            worker.start()
        } catch (_: RuntimeException) {
            complete(appContext, current, CallAudioRecorder.Result(failure = CallAudioRecorder.Failure.SOURCE_UNAVAILABLE))
        }
    }

    @Synchronized
    fun serviceStartFailed(context: Context, id: String) {
        val current = session ?: return
        if (current.id != id) return
        complete(context, current, CallAudioRecorder.Result(failure = CallAudioRecorder.Failure.FOREGROUND_UNAVAILABLE))
    }

    @Synchronized
    fun serviceDestroyed(service: CallService) {
        val current = session ?: return
        if (current.service === service) {
            current.service = null
            stop(stoppedByUser = false)
        }
    }

    @Synchronized
    private fun started(context: Context, current: Session) {
        if (session !== current || current.state != State.STARTING) return
        current.state = State.RECORDING
        current.service?.updateRecordingNotification()
        notifyStateChanged()
        context.toast(R.string.call_recording_started_call_audio)
        if (context.config.playBeepWhenRecording) playStartBeep()
    }

    /** Nonblocking stop. The completion callback, not this request, reports save success. */
    @Synchronized
    fun stop(stoppedByUser: Boolean = false) {
        if (stoppedByUser) {
            userStoppedThisSession = true
            pendingStart = null
        }
        val current = session ?: return
        if (current.state == State.STOPPING) return
        current.state = State.STOPPING
        current.worker?.cancel()
        current.service?.updateRecordingNotification()
        notifyStateChanged()
    }

    @Synchronized
    fun setHolding(holding: Boolean) {
        session?.let {
            it.holding = holding
            it.worker?.holding = holding
        }
    }

    @Synchronized
    fun endSession() {
        stop()
        generation++
        pendingStart = null
        userStoppedThisSession = false
        automaticFailureReported = false
    }

    @Synchronized
    private fun complete(context: Context, current: Session, result: CallAudioRecorder.Result) {
        if (session !== current) return
        current.service?.finishRecording(current.id)
        session = null
        if (result.failure != null && current.generation == generation) automaticFailureReported = true
        notifyStateChanged()
        val message = when {
            result.file != null && result.failure != null -> R.string.call_recording_partial
            result.file != null -> R.string.call_recording_stopped
            result.failure == CallAudioRecorder.Failure.NO_AUDIO -> R.string.call_recording_no_audio
            result.failure == CallAudioRecorder.Failure.BLOCKED -> R.string.call_recording_blocked
            result.failure == CallAudioRecorder.Failure.STORAGE -> R.string.call_recording_storage_failed
            result.failure == CallAudioRecorder.Failure.FOREGROUND_UNAVAILABLE -> R.string.call_recording_service_failed
            result.failure != null -> R.string.call_recording_failed
            else -> null // Cancelled before capture started.
        }
        if (message != null) context.toast(message)
        val pending = pendingStart
        pendingStart = null
        if (pending != null && pending.generation == generation) {
            start(pending.context, automatic = true, label = pending.label)
        }
    }

    /** Keep legacy M4A recordings readable; new captures use lossless PCM/WAV. */
    fun getRecordings(context: Context): List<File> {
        cleanupIncompleteRecordings(context)
        return getRecordingsDirectory(context).listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("wav", "m4a")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteRecording(file: File): Boolean = try {
        file.delete()
    } catch (_: Exception) {
        false
    }

    /** Crash leftovers are removed only when no worker could still be writing/finalizing them. */
    @Synchronized
    fun cleanupIncompleteRecordings(context: Context) {
        if (session != null) return
        getRecordingsDirectory(context).listFiles { file -> file.isFile && file.name.endsWith(".inprogress") }
            ?.forEach { deleteRecording(it) }
    }

    private fun playStartBeep() {
        // Local only; Telecom does not guarantee that the other participant hears it.
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (_: RuntimeException) {
            tone?.release()
            return
        }
        val startedTone = tone
        handler.postDelayed({ startedTone?.release() }, 500)
    }

    private fun notifyStateChanged() {
        EventBus.getDefault().post(Events.RecordingStateChanged)
    }
}
