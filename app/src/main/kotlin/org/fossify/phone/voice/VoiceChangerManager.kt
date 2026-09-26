package org.fossify.phone.voice

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.extensions.getStateCompat
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallRecordingManager
import org.fossify.phone.helpers.SingleCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

/** Main-thread coordinator. No preferences, automatic activation, extra service, or background DSP. */
object VoiceChangerManager {
    enum class State { OFF, STARTING, TRIAL, STOPPING }
    private class Session(val call: Call, var effect: VoiceEffect, val started: Long) {
        lateinit var engine: CallVoiceEngine
        var state = State.STARTING
        var stopReason: String? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null

    fun isEnabled(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        context.resources.getBoolean(R.bool.experimental_voice_changer) &&
        CallRecordingManager.isFeatureEnabled(context)

    fun isBusy(): Boolean = session != null
    fun state(): State = session?.state ?: State.OFF
    fun effect(): VoiceEffect? = session?.effect

    /** Initial safety gate; metadata changes revoke a trial and live state is monitored separately. */
    @TargetApi(33)
    private fun eligibleCall(context: Context): Call {
        check(isEnabled(context)) { "Voice trials are not enabled in this build" }
        check(!CallRecordingManager.isRecording()) { "Stop recording before a voice trial" }
        val call = (CallManager.getPhoneState() as? SingleCall)?.call ?: error("Use a single cellular call")
        check(call.getStateCompat() == Call.STATE_ACTIVE) { "Wait until the call is answered" }
        val details = call.details ?: error("Call details unavailable")
        check(call.children.isEmpty() && !details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) { "Conferences are not supported" }
        check(!details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) &&
            !details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)) { "Emergency calls are excluded" }
        val number = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        check(!number.isNullOrBlank()) { "Cannot verify call safety without a telephone number" }
        check(!context.getSystemService(TelephonyManager::class.java).isEmergencyNumber(number)) { "Emergency calls are excluded" }
        val account = context.getSystemService(TelecomManager::class.java).getPhoneAccount(details.accountHandle)
        check(account?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true) { "Only SIM calls are supported" }
        val audio = CallManager.inCallService?.callAudioState ?: error("Call audio state unavailable")
        check(audio.route == CallAudioState.ROUTE_EARPIECE && !audio.isMuted) { "Use the earpiece with call mute off" }
        return call
    }

    fun select(context: Context, effect: VoiceEffect) {
        check(Looper.myLooper() == Looper.getMainLooper())
        try {
            check(context is LifecycleOwner && context.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                "Keep the call screen visible during a voice trial"
            }
            val call = eligibleCall(context)
            val existing = session
            if (existing != null) {
                check(existing.call === call && existing.state == State.TRIAL) { "Wait for the voice trial to start or stop" }
                check(existing.effect != VoiceEffect.SILENCE_CHECK && effect != VoiceEffect.SILENCE_CHECK) {
                    "Stop the current trial before a silence check"
                }
                existing.engine.select(effect)
                existing.effect = effect
                notifyChanged()
                return
            }
            val app = context.applicationContext
            val next = Session(call, effect, SystemClock.elapsedRealtime())
            next.engine = CallVoiceEngine(app, effect,
                onRunning = { handler.post {
                    if (session === next && next.state == State.STARTING) {
                        next.state = State.TRIAL
                        notifyChanged()
                    }
                } },
                onStopped = { reason -> handler.post {
                    if (session === next) {
                        session = null
                        handler.removeCallbacks(monitor)
                        notifyChanged()
                        val message = reason ?: next.stopReason
                        if (message != null) app.toast(app.getString(R.string.voice_trial_error, message))
                        else app.toast(R.string.voice_trial_finished)
                    }
                } })
            session = next
            notifyChanged()
            try { next.engine.start() } catch (error: Exception) {
                session = null
                notifyChanged()
                throw error
            }
            handler.post(monitor)
        } catch (error: Exception) {
            context.toast(context.getString(R.string.voice_trial_error, error.message ?: error.javaClass.simpleName))
        }
    }

    fun stop(reason: String? = null) {
        val current = session ?: return
        if (current.state == State.STOPPING) return
        current.stopReason = reason
        current.state = State.STOPPING
        current.engine.cancel()
        handler.removeCallbacks(monitor)
        notifyChanged()
    }

    /** Telecom changes always revoke a trial; no automatic restart after hold/swap/mute/route changes. */
    fun onCallChanged() {
        if (session != null) stop()
    }

    private val monitor = object : Runnable {
        override fun run() {
            val current = session ?: return
            if (current.state == State.STOPPING) return
            // An independent UI-thread deadline can withdraw mode even if a vendor PCM call stalls.
            if (current.engine.hasExpired(SystemClock.elapsedRealtime())) {
                stop()
                return
            }
            try {
                val context = CallManager.inCallService ?: error("Call service unavailable")
                check((CallManager.getPhoneState() as? SingleCall)?.call === current.call &&
                    current.call.getStateCompat() == Call.STATE_ACTIVE) { "Call changed" }
                val audio = context.callAudioState
                check(audio?.route == CallAudioState.ROUTE_EARPIECE && !audio.isMuted && !CallRecordingManager.isRecording()) {
                    "Call audio changed"
                }
                check(current.state != State.STARTING || SystemClock.elapsedRealtime() - current.started < 5_000) {
                    "Voice trial startup timed out"
                }
            } catch (error: Exception) {
                stop(error.message ?: "Call audio changed")
                return
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun notifyChanged() { EventBus.getDefault().post(Events.VoiceEffectStateChanged) }
}
