package org.fossify.phone.voice

import android.annotation.TargetApi
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock

/**
 * Finite, foreground-UI-only experimental bridge: mic -> DSP -> telephony TX AND
 * telephony RX -> earpiece. MODE_CALL_REDIRECT is intended to remove the native call patches;
 * only a remote silence check can establish whether a vendor HAL actually removes dry mic audio.
 * Never use microphone mute for suppression: that can also silence the DSP's input.
 */
@TargetApi(33)
internal class CallVoiceEngine(
    private val context: Context,
    initialEffect: VoiceEffect,
    private val onRunning: () -> Unit,
    private val onStopped: (String?) -> Unit,
) : Thread("CallVoiceTrial") {
    @Volatile private var cancelled = false
    @Volatile private var selectedEffect = initialEffect
    private val initialEffect = initialEffect
    @Volatile private var modeLease: AudioModeLease? = null
    @Volatile private var cleanupFailed = false
    @Volatile private var activeTrial: VoiceTrial? = null

    fun hasExpired(nowMs: Long): Boolean = activeTrial?.isExpired(nowMs) == true

    fun select(effect: VoiceEffect) {
        require(initialEffect != VoiceEffect.SILENCE_CHECK && effect != VoiceEffect.SILENCE_CHECK)
        selectedEffect = effect
    }

    fun cancel() {
        cancelled = true
        interrupt() // All reads/writes are non-blocking; wakes the short polling sleep.
        // Withdraw even if a vendor stream constructor is slow. AudioService schedules mode
        // changes; do not wait for worker joins or PCM progress to relinquish our ownership.
        try { modeLease?.close() } catch (_: Exception) { cleanupFailed = true }
    }

    override fun run() {
        var failure: String? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (!cancelled) bridge()
        } catch (error: Exception) {
            if (!cancelled) {
                // No audio, numbers, Call.toString(), or stack traces in logs/UI.
                failure = error.message ?: error.javaClass.simpleName
            }
        } finally {
            onStopped(if (cleanupFailed) "Audio cleanup failed; end the test call and restart the app if sound did not return" else failure)
        }
    }

    @SuppressLint("MissingPermission") // Checked again by CallAudioAccess and Android itself.
    private fun bridge() {
        val access = CallAudioAccess(context)
        val manager = access.manager
        check(manager.mode == AudioManager.MODE_IN_CALL) { "A cellular call must already be active" }
        check(!manager.isMicrophoneMute) { "Turn off call mute before starting the trial" }
        val tx = access.output(AudioDeviceInfo.TYPE_TELEPHONY)
        val rx = access.input(AudioDeviceInfo.TYPE_TELEPHONY)
        val earpiece = access.output(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val mic = access.input(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        val inputFormat = format(AudioFormat.CHANNEL_IN_MONO)
        val outputFormat = format(AudioFormat.CHANNEL_OUT_MONO)
        var microphone: AudioRecord? = null
        var receive: AudioRecord? = null
        var send: AudioTrack? = null
        var listen: AudioTrack? = null
        val lease = AudioModeLease(
            enter = { manager.mode = access.redirectMode },
            withdraw = { manager.mode = AudioManager.MODE_NORMAL },
        )
        modeLease = lease
        try {
            if (cancelled) return
            // Mark ownership BEFORE the request so a throwing/partly accepted request is cleaned up.
            if (!lease.acquire()) return
            val modeDeadline = SystemClock.elapsedRealtime() + 1_000
            while (manager.mode != access.redirectMode && !cancelled) {
                check(SystemClock.elapsedRealtime() < modeDeadline) { "ROM refused call redirection" }
                sleep(10)
            }
            if (cancelled) return

            send = access.uplink(outputFormat)
            if (cancelled) return
            check(send.state == AudioTrack.STATE_INITIALIZED && send.setPreferredDevice(tx)) { "Cannot route call uplink" }
            receive = access.downlink(inputFormat)
            if (cancelled) return
            check(receive.state == AudioRecord.STATE_INITIALIZED && receive.setPreferredDevice(rx)) { "Cannot route call downlink" }
            val recordMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(recordMin > 0) { "16 kHz microphone capture unsupported" }
            microphone = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(inputFormat)
                .setBufferSizeInBytes(maxOf(recordMin, SAMPLE_RATE / 5)) // PCM16: >=100ms capacity.
                .build()
            check(microphone.state == AudioRecord.STATE_INITIALIZED && microphone.setPreferredDevice(mic)) { "Cannot route microphone" }
            val playbackMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(playbackMin > 0) { "16 kHz earpiece playback unsupported" }
            listen = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(outputFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(playbackMin, SAMPLE_RATE / 5))
                .build()
            check(listen.state == AudioTrack.STATE_INITIALIZED && listen.setPreferredDevice(earpiece)) { "Cannot route received audio" }
            if (cancelled) return
            send.play()
            if (cancelled) return
            listen.play()
            if (cancelled) return
            receive.startRecording()
            if (cancelled) return
            microphone.startRecording()
            if (cancelled) return
            check(send.playState == AudioTrack.PLAYSTATE_PLAYING && listen.playState == AudioTrack.PLAYSTATE_PLAYING &&
                receive.recordingState == AudioRecord.RECORDSTATE_RECORDING && microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Call audio streams did not start"
            }
            val sourceMic = microphone
            val sourceRx = receive
            val sinkTx = send
            val sinkEar = listen
            val started = SystemClock.elapsedRealtime()
            val trial = VoiceTrial(started, initialEffect)
            activeTrial = trial
            val dsp = VoiceEffectProcessor(SAMPLE_RATE)
            val uplink = PcmPipe(FRAME_SAMPLES,
                read = { sourceMic.read(it, 0, it.size, AudioRecord.READ_NON_BLOCKING) },
                write = { data, offset, count -> if (cancelled) 0 else sinkTx.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING) },
                transform = { data, count -> dsp.select(selectedEffect); dsp.process(data, count) },
                startedAtMs = started)
            val downlink = PcmPipe(FRAME_SAMPLES,
                read = { sourceRx.read(it, 0, it.size, AudioRecord.READ_NON_BLOCKING) },
                write = { data, offset, count -> sinkEar.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING) },
                startedAtMs = started)
            var nextRouteCheck = started
            var announced = false
            while (!cancelled && !trial.isExpired(SystemClock.elapsedRealtime())) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextRouteCheck) {
                    check(manager.mode == access.redirectMode && !manager.isMicrophoneMute) { "Call audio mode or mute changed" }
                    val routed = arrayOf(sourceMic.routedDevice, sourceRx.routedDevice, sinkTx.routedDevice, sinkEar.routedDevice)
                    val expected = intArrayOf(mic.id, rx.id, tx.id, earpiece.id)
                    for (i in routed.indices) {
                        check((routed[i] == null && now - started < 1_000) || routed[i]?.id == expected[i]) {
                            "Call audio device route was not preserved"
                        }
                    }
                    check(sourceMic.activeRecordingConfiguration?.isClientSilenced != true &&
                        sourceRx.activeRecordingConfiguration?.isClientSilenced != true) { "Android silenced call audio capture" }
                    if (!announced && routed.all { it != null }) {
                        announced = true
                        onRunning() // Streams routed, NOT proof of remote audibility or dry-mic removal.
                    }
                    nextRouteCheck = now + 100
                }
                if (cancelled) break
                downlink.step(now)
                if (!cancelled) uplink.step(now)
                sleep(5)
            }
        } finally {
            // Withdraw OUR request first, even if a vendor's resource release later stalls.
            // Telecom resumes ownership; never force MODE_IN_CALL or alter microphone mute.
            try { lease.close() } catch (_: Exception) { cleanupFailed = true }
            // Release every resource even if another release fails. Never join a worker on the UI.
            for (record in arrayOf(microphone, receive)) {
                try { record?.release() } catch (_: Exception) { cleanupFailed = true }
            }
            for (track in arrayOf(send, listen)) {
                try {
                    if (track?.state == AudioTrack.STATE_INITIALIZED) { track.pause(); track.flush() }
                } catch (_: IllegalStateException) {
                    // AudioManager can already have released it after a call/mode change.
                } catch (_: Exception) { cleanupFailed = true }
                try { track?.release() } catch (_: Exception) { cleanupFailed = true }
            }
            modeLease = null
        }
    }

    private fun format(mask: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(mask).build()

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320 // 20ms; at most one pending frame in each direction.
    }
}
