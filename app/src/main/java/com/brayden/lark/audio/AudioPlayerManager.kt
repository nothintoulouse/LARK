package com.brayden.lark.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException

enum class PlayerState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    ERROR
}

class AudioPlayerManager {

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val POSITION_UPDATE_INTERVAL_MS = 200L
    }

    interface Listener {
        fun onStateChanged(state: PlayerState)
        fun onPositionUpdate(positionMs: Int, durationMs: Int)
        fun onPlaybackCompleted()
        fun onError(message: String)
    }

    private var mediaPlayer: MediaPlayer? = null
    private var listener: Listener? = null
    private var currentFileId: Long = -1
    private var currentFilePath: String? = null
    private var _state: PlayerState = PlayerState.IDLE
    private val handler = Handler(Looper.getMainLooper())

    val state: PlayerState get() = _state
    val isPlaying: Boolean get() = _state == PlayerState.PLAYING
    val currentPlayingFileId: Long get() = currentFileId

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            try {
                val mp = mediaPlayer
                if (mp != null && _state == PlayerState.PLAYING) {
                    listener?.onPositionUpdate(mp.currentPosition, mp.duration)
                    handler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaPlayer in bad state during position update")
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Start playback of a WAV file.
     * If a different file is already playing, stop it first.
     * If the same file is paused, resume it.
     */
    fun play(fileId: Long, filePath: String) {
        if (currentFileId == fileId && _state == PlayerState.PAUSED) {
            resume()
            return
        }

        // Stop any current playback
        stop()

        val file = File(filePath)
        if (!file.exists()) {
            setError("File not found: $filePath")
            return
        }

        try {
            currentFileId = fileId
            currentFilePath = filePath
            setState(PlayerState.PREPARING)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    setState(PlayerState.PLAYING)
                    mp.start()
                    startPositionUpdates()
                }
                setOnCompletionListener { mp ->
                    stopPositionUpdates()
                    setState(PlayerState.IDLE)
                    listener?.onPlaybackCompleted()
                    listener?.onPositionUpdate(mp.duration, mp.duration)
                }
                setOnErrorListener { _, what, extra ->
                    setError("MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: IOException) {
            setError("Failed to open file: ${e.message}")
        }
    }

    fun pause() {
        try {
            if (_state == PlayerState.PLAYING) {
                mediaPlayer?.pause()
                setState(PlayerState.PAUSED)
                stopPositionUpdates()
            }
        } catch (e: IllegalStateException) {
            setError("Pause failed: ${e.message}")
        }
    }

    fun resume() {
        try {
            if (_state == PlayerState.PAUSED) {
                mediaPlayer?.start()
                setState(PlayerState.PLAYING)
                startPositionUpdates()
            }
        } catch (e: IllegalStateException) {
            setError("Resume failed: ${e.message}")
        }
    }

    fun togglePlayPause() {
        when (_state) {
            PlayerState.PLAYING -> pause()
            PlayerState.PAUSED -> resume()
            else -> {}
        }
    }

    /**
     * Seek to a position in milliseconds.
     */
    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
            if (_state == PlayerState.PLAYING || _state == PlayerState.PAUSED) {
                listener?.onPositionUpdate(positionMs, mediaPlayer?.duration ?: 0)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Seek failed: ${e.message}")
        }
    }

    fun stop() {
        stopPositionUpdates()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stop/release failed: ${e.message}")
        }
        mediaPlayer = null
        currentFileId = -1
        currentFilePath = null
        setState(PlayerState.IDLE)
    }

    fun getDurationMs(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    fun getCurrentPositionMs(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private fun setState(newState: PlayerState) {
        _state = newState
        listener?.onStateChanged(newState)
    }

    private fun setError(message: String) {
        Log.e(TAG, message)
        listener?.onError(message)
        stop()
    }

    private fun startPositionUpdates() {
        handler.removeCallbacks(positionUpdateRunnable)
        handler.post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        handler.removeCallbacks(positionUpdateRunnable)
    }
}
