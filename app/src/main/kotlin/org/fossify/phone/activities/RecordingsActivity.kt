package org.fossify.phone.activities

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.adapters.RecordingsAdapter
import org.fossify.phone.databinding.ActivityRecordingsBinding
import org.fossify.phone.helpers.CallRecordingManager
import org.fossify.phone.interfaces.RecordingItemListener
import org.fossify.phone.models.RecordingItem
import java.io.File

class RecordingsActivity : SimpleActivity(), RecordingItemListener {
    private val binding by viewBinding(ActivityRecordingsBinding::inflate)
    private var adapter: RecordingsAdapter? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playingItem: RecordingItem? = null
    private val progressHandler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null) {
                try {
                    adapter?.updatePlaybackProgress(player.currentPosition, player.duration)
                } catch (_: Exception) {
                }
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (!CallRecordingManager.isFeatureEnabled(this)) {
            finish()
            return
        }

        setupEdgeToEdge(padBottomSystem = listOf(binding.recordingsHolder))
        updateTextColors(binding.recordingsHolder)
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.recordingsToolbar, NavigationIcon.Arrow)
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressTick)
        releasePlayer(notify = false)
    }

    private fun loadRecordings() {
        ensureBackgroundThread {
            val recordings = CallRecordingManager.getRecordings(this).map { file ->
                RecordingItem(file, getDurationMs(file))
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
                val currentAdapter = adapter
                if (currentAdapter == null) {
                    adapter = RecordingsAdapter(recordings.toMutableList(), this)
                    binding.recordingsList.adapter = adapter
                } else {
                    currentAdapter.updateItems(recordings)
                }
            }
        }
    }

    private fun getDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun getCurrentRecordingPath(): String? = playingItem?.file?.absolutePath

    override fun isRecordingPlaying(): Boolean = mediaPlayer?.isPlaying == true

    override fun getPlaybackPositionMs(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun onPlayPauseRecording(item: RecordingItem) {
        val isSameItem = playingItem?.file?.absolutePath == item.file.absolutePath
        if (isSameItem) {
            val player = mediaPlayer ?: return
            try {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.currentPosition >= player.duration - 100) {
                        player.seekTo(0)
                    }
                    player.start()
                    progressHandler.post(progressTick)
                }
                adapter?.notifyItemChangedForPath(item.file.absolutePath)
            } catch (_: Exception) {
                releasePlayer()
                toast(R.string.recording_playback_failed)
            }
            return
        }

        releasePlayer(notify = true)
        playingItem = item
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(item.file.absolutePath)
            player.setOnPreparedListener {
                try {
                    it.start()
                    progressHandler.post(progressTick)
                } catch (_: Exception) {
                }
                adapter?.notifyItemChangedForPath(item.file.absolutePath)
            }
            player.setOnCompletionListener {
                try {
                    it.seekTo(0)
                    it.pause()
                } catch (_: Exception) {
                }
                progressHandler.removeCallbacks(progressTick)
                adapter?.notifyItemChangedForPath(item.file.absolutePath)
            }
            player.setOnErrorListener { _, _, _ ->
                releasePlayer()
                toast(R.string.recording_playback_failed)
                true
            }
            player.prepareAsync()
            adapter?.notifyItemChangedForPath(item.file.absolutePath)
        } catch (_: Exception) {
            releasePlayer()
            toast(R.string.recording_playback_failed)
        }
    }

    override fun onRecordingSeek(item: RecordingItem, positionMs: Int) {
        if (playingItem?.file?.absolutePath != item.file.absolutePath) return
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (_: Exception) {
        }
        adapter?.updatePlaybackProgress(positionMs, item.durationMs.coerceAtLeast(1L).toInt())
    }

    override fun onShareRecording(item: RecordingItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", item.file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_recording)))
        } catch (_: Exception) {
            toast(R.string.recording_share_failed)
        }
    }

    override fun onDeleteRecording(item: RecordingItem) {
        val question = "${getString(R.string.delete_recording_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                CallRecordingManager.deleteRecording(item.file)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (playingItem?.file?.absolutePath == item.file.absolutePath) {
                        releasePlayer(notify = false)
                    }
                    toast(R.string.recording_deleted)
                    loadRecordings()
                }
            }
        }
    }

    private fun releasePlayer(notify: Boolean = true) {
        val previousPath = playingItem?.file?.absolutePath
        progressHandler.removeCallbacks(progressTick)
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        playingItem = null
        if (notify && previousPath != null) {
            adapter?.notifyItemChangedForPath(previousPath)
        }
    }
}
