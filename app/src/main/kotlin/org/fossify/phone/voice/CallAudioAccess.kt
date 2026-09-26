package org.fossify.phone.voice

import android.annotation.TargetApi
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build

/**
 * Android 13+ privileged SystemApi adapter. No root subprocess, global hidden-API bypass,
 * ordinary speaker-playback fallback, or claim that finding a telephony port proves replacement.
 * BCP 57977e5526b6ad3983d7db9c8a64236e6f1eff15 is the telephony-output reference (GPLv3).
 * Uplink uses BCP's public AudioTrack + TYPE_TELEPHONY route. Downlink extraction and
 * exclusive call redirection still use AOSP SystemApis; BCP does not implement those.
 */
@TargetApi(33)
internal class CallAudioAccess(context: Context) {
    val manager: AudioManager = context.getSystemService(AudioManager::class.java)
    val redirectMode: Int
    private val downlink = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)

    init {
        check(Build.VERSION.SDK_INT >= 33) { "Call redirection requires Android 13+" }
        for (permission in arrayOf(
            Manifest.permission.RECORD_AUDIO,
            "android.permission.CALL_AUDIO_INTERCEPTION",
            "android.permission.MODIFY_PHONE_STATE",
        )) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                "Missing $permission; the opt-in privileged module is required"
            }
        }
        redirectMode = AudioManager::class.java.getField("MODE_CALL_REDIRECT").getInt(null)
        check(AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(manager) == true) {
            "Android does not expose call interception on this device"
        }
    }

    fun output(type: Int): AudioDeviceInfo = device(AudioManager.GET_DEVICES_OUTPUTS, type)
    fun input(type: Int): AudioDeviceInfo = device(AudioManager.GET_DEVICES_INPUTS, type)
    private fun device(direction: Int, type: Int): AudioDeviceInfo =
        manager.getDevices(direction).firstOrNull { it.type == type }
            ?: error("Required call audio device unavailable ($type)")

    fun uplink(format: AudioFormat): AudioTrack = TelephonyPcmOutput.create(manager, format)
    fun downlink(format: AudioFormat): AudioRecord = downlink.invoke(manager, format) as AudioRecord
}
