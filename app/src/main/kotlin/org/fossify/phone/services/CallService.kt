package org.fossify.phone.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.R
import org.fossify.phone.activities.RecordingsActivity
import org.fossify.phone.helpers.STOP_RECORDING
import org.fossify.phone.receivers.CallActionReceiver
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
    private var recordingSessionId: String? = null
    private var lastRecordingStartId = 0
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
            CallRecordingManager.endSession()
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            CallRecordingManager.setHolding(!CallManager.hasActiveCall())
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    /**
     * One mixed stream per session, including swaps/conferences. While all calls are held, the
     * input is drained without writing held audio. Stop asynchronously when all calls disconnect;
     * this same call service stays alive to finalize the file. Manual stop suppresses auto-restart.
     */
    private fun updateCallRecording(call: Call, state: Int) {
        if (!CallManager.hasOngoingCall()) {
            CallRecordingManager.endSession()
            return
        }
        CallRecordingManager.setHolding(!CallManager.hasActiveCall())
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (id == null || !CallRecordingManager.isExpectedServiceRequest(id)) {
            // InCallService is exported for Telecom, but BIND_INCALL_SERVICE protects it.
            // Also reject stale or unsolicited start commands without stopping an active recorder.
            if (!CallRecordingManager.isRecording()) stopSelf(startId)
            return START_NOT_STICKY
        }
        recordingSessionId = id
        lastRecordingStartId = startId
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.call_recordings), NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null) }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, recordingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, recordingNotification())
            }
            CallRecordingManager.serviceReady(this, id)
        } catch (error: RuntimeException) {
            Log.w("CallService", "Cannot run recording service (${error.javaClass.simpleName})")
            CallRecordingManager.serviceStartFailed(this, id)
            finishRecording(id)
        }
        return START_NOT_STICKY // Never resurrect a recorder after process death without an active call.
    }

    fun updateRecordingNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, recordingNotification())
    }

    fun finishRecording(id: String) {
        if (recordingSessionId != id) return
        recordingSessionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Removes the started lifetime only. Telecom can keep the same service bound for calls.
        stopSelf(lastRecordingStartId)
    }

    override fun onDestroy() {
        CallRecordingManager.serviceDestroyed(this)
        CallRecordingManager.endSession()
        EventBus.getDefault().unregister(this)
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }

    private fun recordingNotification(): Notification {
        val state = CallRecordingManager.state()
        val text = when (state) {
            CallRecordingManager.State.STARTING -> R.string.call_recording_starting
            CallRecordingManager.State.STOPPING -> R.string.call_recording_saving
            else -> R.string.call_recording_in_progress
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RecordingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            this, 0, Intent(this, CallActionReceiver::class.java).setAction(STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record_call_vector)
            .setContentTitle(getString(R.string.call_recordings))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (state != CallRecordingManager.State.STOPPING) {
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.stop_call_recording), stop).build()
            )
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_SESSION_ID = "recording_session_id"
        private const val CHANNEL = "call_recording"
        private const val NOTIFICATION_ID = 43
    }
}
