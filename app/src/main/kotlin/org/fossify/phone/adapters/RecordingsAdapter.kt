package org.fossify.phone.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemRecordingBinding
import org.fossify.phone.interfaces.RecordingItemListener
import org.fossify.phone.models.RecordingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    var items: MutableList<RecordingItem>,
    private val listener: RecordingItemListener
) : RecyclerView.Adapter<RecordingsAdapter.RecordingViewHolder>() {

    private var playingHolder: RecordingViewHolder? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RecordingViewHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: RecordingViewHolder) {
        super.onViewRecycled(holder)
        if (playingHolder === holder) {
            playingHolder = null
        }
    }

    fun updateItems(newItems: List<RecordingItem>) {
        items.clear()
        items.addAll(newItems)
        playingHolder = null
        notifyDataSetChanged()
    }

    fun updatePlaybackProgress(positionMs: Int, durationMs: Int) {
        playingHolder?.updateProgress(positionMs, durationMs)
    }

    fun notifyItemChangedForPath(path: String?) {
        val index = items.indexOfFirst { it.file.absolutePath == path }
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    inner class RecordingViewHolder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
        private var boundPath: String? = null

        fun bind(item: RecordingItem) = binding.apply {
            boundPath = item.file.absolutePath
            val isCurrent = listener.getCurrentRecordingPath() == boundPath
            val textColor = root.context.getProperTextColor()

            recordingTitle.text = item.file.name.removeSuffix(".m4a")
            recordingInfo.text = root.context.getString(
                R.string.recording_info,
                dateFormat.format(Date(item.file.lastModified())),
                formatDuration(item.durationMs),
                android.text.format.Formatter.formatFileSize(root.context, item.file.length())
            )

            arrayOf(recordingPlayPause, recordingShare, recordingDelete).forEach {
                it.applyColorFilter(textColor)
            }

            val isPlaying = isCurrent && listener.isRecordingPlaying()
            recordingPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause_recording_vector else R.drawable.ic_play_recording_vector
            )
            recordingPlayPause.contentDescription = root.context.getString(
                if (isPlaying) R.string.pause_recording else R.string.play_recording
            )

            recordingPlaybackHolder.beVisibleIf(isCurrent)
            if (isCurrent) {
                val duration = item.durationMs.coerceAtLeast(1L).toInt()
                recordingSeekbar.max = duration
                updateProgress(listener.getPlaybackPositionMs(), duration)
            }

            recordingPlayPause.setOnClickListener { listener.onPlayPauseRecording(item) }
            recordingShare.setOnClickListener { listener.onShareRecording(item) }
            recordingDelete.setOnClickListener { listener.onDeleteRecording(item) }
            root.setOnClickListener { listener.onPlayPauseRecording(item) }

            recordingSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updateTimeText(progress, seekBar?.max ?: 0)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    listener.onRecordingSeek(item, seekBar?.progress ?: 0)
                }
            })

            if (isCurrent) {
                playingHolder = this@RecordingViewHolder
            } else if (playingHolder === this@RecordingViewHolder) {
                playingHolder = null
            }
        }

        fun updateProgress(positionMs: Int, durationMs: Int) {
            if (boundPath != listener.getCurrentRecordingPath()) return
            binding.recordingSeekbar.progress = positionMs.coerceAtMost(durationMs)
            updateTimeText(positionMs, durationMs)
        }

        private fun updateTimeText(positionMs: Int, durationMs: Int) {
            binding.recordingTime.text = binding.root.context.getString(
                R.string.recording_progress,
                formatDuration(positionMs.toLong()),
                formatDuration(durationMs.toLong())
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
