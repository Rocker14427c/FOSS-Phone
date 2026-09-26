package org.fossify.phone.interfaces

import org.fossify.phone.models.RecordingItem

interface RecordingItemListener {
    fun getCurrentRecordingPath(): String?
    fun isRecordingPlaying(): Boolean
    fun getPlaybackPositionMs(): Int
    fun onPlayPauseRecording(item: RecordingItem)
    fun onRecordingSeek(item: RecordingItem, positionMs: Int)
    fun onShareRecording(item: RecordingItem)
    fun onDeleteRecording(item: RecordingItem)
}
