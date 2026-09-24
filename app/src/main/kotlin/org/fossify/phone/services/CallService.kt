package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.CallRecordingManager
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (CallManager.getPhoneState() == NoCall) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
            updateCallRecording(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        EventBus.getDefault().register(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)
        updateCallRecording(call, call.state)

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            // the whole call session is over, save any recording and reset the session state
            CallRecordingManager.endSession(this)
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    /**
     * A single recording covers the whole call session: it keeps running across hold, swap and
     * multiple calls and is stopped only when every call has ended. If the user manually stopped
     * recording, automatic restarts stay off for the rest of the session.
     */
    private fun updateCallRecording(call: Call, state: Int) {
        if (state == Call.STATE_ACTIVE && config.autoRecordCalls) {
            CallRecordingManager.start(
                context = this,
                automatic = true,
                label = call.details?.handle?.schemeSpecificPart
            )
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRecordingStateChanged(event: Events.RecordingStateChanged) {
        if (CallManager.getPhoneState() != NoCall) {
            callNotificationManager.setupNotification()
        }
    }

    override fun onDestroy() {
        CallRecordingManager.endSession(this)
        EventBus.getDefault().unregister(this)
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}
