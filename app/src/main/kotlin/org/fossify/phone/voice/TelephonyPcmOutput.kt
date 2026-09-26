/*
 * Telephony AudioTrack construction adapted from chenxiaolong/BCP, PlayerThread.kt
 * https://github.com/chenxiaolong/BCP/blob/57977e5526b6ad3983d7db9c8a64236e6f1eff15/app/src/main/java/com/chiller3/bcp/PlayerThread.kt
 * BCP is GPL-3.0; this project is distributed under GPL-3.0 (see LICENSE).
 * This port removes file decoding: live processed PCM is supplied by CallVoiceEngine.
 */
package org.fossify.phone.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Never substitute an ordinary local playback device if the telephony endpoint is unavailable. */
internal object TelephonyPcmOutput {
    fun create(manager: AudioManager, format: AudioFormat): AudioTrack {
        val telephony = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?: error("No telephony output device; voice effects cannot reach the other caller")
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
        check(minimum > 0) { "Telephony PCM format is not supported ($minimum)" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minimum)
            .build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED && track.setPreferredDevice(telephony)) {
                "Cannot select the telephony uplink; no local playback fallback is allowed"
            }
            return track
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }
}
