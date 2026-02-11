package com.brayden.lark.ui.files

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brayden.lark.audio.PlayerState
import com.brayden.lark.data.local.AudioFileEntity
import com.brayden.lark.data.model.FileState
import com.brayden.lark.databinding.ItemFileBinding
import com.brayden.lark.util.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private val onShareClick: (AudioFileEntity) -> Unit,
    private val onDeleteClick: (AudioFileEntity) -> Unit,
    private val onConvertClick: (AudioFileEntity) -> Unit,
    private val onPlayClick: (AudioFileEntity) -> Unit,
    private val onTogglePlayPause: (Long) -> Unit,
    private val onSeek: (Int) -> Unit
) : ListAdapter<AudioFileEntity, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    companion object {
        private const val PAYLOAD_PLAYBACK = "playback"
        private const val PAYLOAD_EXPAND = "expand"
    }

    private var currentPlaybackState: PlaybackState = PlaybackState()
    private var expandedFileId: Long = -1

    fun updatePlaybackState(state: PlaybackState) {
        val previousFileId = currentPlaybackState.fileId
        currentPlaybackState = state

        // Use payload to do a partial bind — only update player controls, not the whole card
        if (previousFileId != state.fileId) {
            val prevPos = findPositionById(previousFileId)
            val newPos = findPositionById(state.fileId)
            if (prevPos != RecyclerView.NO_POSITION) notifyItemChanged(prevPos, PAYLOAD_PLAYBACK)
            if (newPos != RecyclerView.NO_POSITION) notifyItemChanged(newPos, PAYLOAD_PLAYBACK)
        } else {
            val pos = findPositionById(state.fileId)
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos, PAYLOAD_PLAYBACK)
        }
    }

    private fun findPositionById(fileId: Long): Int {
        for (i in 0 until itemCount) {
            if (getItem(i).id == fileId) return i
        }
        return RecyclerView.NO_POSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // Full bind
            holder.bind(getItem(position))
        } else {
            // Partial bind — only update what changed
            val file = getItem(position)
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_PLAYBACK -> holder.bindPlaybackState(file.id)
                    PAYLOAD_EXPAND -> holder.bindExpandState(file)
                }
            }
        }
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isSeeking = false

        init {
            // Set up SeekBar listener once in init, not on every bind
            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.tvCurrentTime.text = formatPlayerTime(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = false
                    seekBar?.let { onSeek(it.progress) }
                }
            })
        }

        @SuppressLint("SetTextI18n")
        fun bind(file: AudioFileEntity) {
            binding.tvFileName.text = file.filename

            val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
                .format(Date(file.createdAt))
            val durationStr = FileUtils.formatDuration(file.durationMs)
            binding.tvFileInfo.text = "$dateStr | $durationStr"

            // Show both file sizes
            val opusSize = FileUtils.formatFileSize(file.sizeBytes)
            val wavSize = if (file.wavPath != null) {
                val wavFile = File(file.wavPath)
                if (wavFile.exists()) FileUtils.formatFileSize(wavFile.length()) else "N/A"
            } else {
                "N/A"
            }
            binding.tvFileSizes.text = "Opus: $opusSize | WAV: $wavSize"

            val state = try {
                FileState.valueOf(file.state)
            } catch (e: Exception) {
                FileState.ERROR
            }

            binding.tvFileState.text = when (state) {
                FileState.RECORDING -> "Recording"
                FileState.OPUS_READY -> "Opus Only"
                FileState.WAV_CONVERTING -> "Converting..."
                FileState.COMPLETE -> "WAV Ready"
                FileState.ERROR -> "Error"
            }

            binding.btnConvert.isVisible = state == FileState.OPUS_READY || state == FileState.ERROR
            binding.btnConvert.setOnClickListener { onConvertClick(file) }
            binding.btnShare.setOnClickListener { onShareClick(file) }
            binding.btnDelete.setOnClickListener { onDeleteClick(file) }

            val hasWav = state == FileState.COMPLETE && file.wavPath != null

            // Header tap toggles expand + auto-play
            binding.layoutFileHeader.setOnClickListener {
                val wasExpanded = expandedFileId == file.id
                val previousExpandedId = expandedFileId
                expandedFileId = if (wasExpanded) -1 else file.id

                // Notify only the items that changed, with expand payload
                val prevPos = findPositionById(previousExpandedId)
                if (prevPos != RecyclerView.NO_POSITION && previousExpandedId != file.id) {
                    notifyItemChanged(prevPos, PAYLOAD_EXPAND)
                }
                @Suppress("DEPRECATION")
                val currentPos = adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(currentPos, PAYLOAD_EXPAND)
                }

                if (!wasExpanded && hasWav) {
                    onPlayClick(file)
                }
            }

            // Play/Pause button
            binding.btnPlayPause.setOnClickListener {
                if (currentPlaybackState.fileId == file.id &&
                    (currentPlaybackState.playerState == PlayerState.PLAYING ||
                     currentPlaybackState.playerState == PlayerState.PAUSED)
                ) {
                    onTogglePlayPause(file.id)
                } else if (hasWav) {
                    onPlayClick(file)
                }
            }

            // Bind expand + playback state
            bindExpandState(file)
            bindPlaybackState(file.id)
        }

        fun bindExpandState(file: AudioFileEntity) {
            val isExpanded = expandedFileId == file.id
            val state = try {
                FileState.valueOf(file.state)
            } catch (e: Exception) {
                FileState.ERROR
            }
            val hasWav = state == FileState.COMPLETE && file.wavPath != null
            binding.layoutPlayer.isVisible = isExpanded && hasWav
        }

        fun bindPlaybackState(fileId: Long) {
            if (currentPlaybackState.fileId == fileId) {
                val ps = currentPlaybackState

                binding.btnPlayPause.setImageResource(
                    if (ps.playerState == PlayerState.PLAYING)
                        android.R.drawable.ic_media_pause
                    else
                        android.R.drawable.ic_media_play
                )

                if (ps.durationMs > 0) {
                    binding.seekBar.max = ps.durationMs
                    // Only update SeekBar if user is not dragging it
                    if (!isSeeking) {
                        binding.seekBar.progress = ps.positionMs
                    }
                }

                binding.tvCurrentTime.text = formatPlayerTime(ps.positionMs)
                binding.tvTotalTime.text = formatPlayerTime(ps.durationMs)
            } else {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.seekBar.progress = 0
                binding.tvCurrentTime.text = "0:00"
                binding.tvTotalTime.text = "0:00"
            }
        }

        private fun formatPlayerTime(ms: Int): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<AudioFileEntity>() {
        override fun areItemsTheSame(oldItem: AudioFileEntity, newItem: AudioFileEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AudioFileEntity, newItem: AudioFileEntity) =
            oldItem == newItem
    }
}
