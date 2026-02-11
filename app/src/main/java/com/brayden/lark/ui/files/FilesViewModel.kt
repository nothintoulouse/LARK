package com.brayden.lark.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.brayden.lark.audio.AudioPlayerManager
import com.brayden.lark.audio.PlayerState
import com.brayden.lark.data.local.AudioFileEntity
import com.brayden.lark.data.repository.AudioFileRepository
import com.brayden.lark.service.WavConversionWorker
import com.brayden.lark.util.FileUtils
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val fileId: Long = -1,
    val playerState: PlayerState = PlayerState.IDLE,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudioFileRepository(application)
    val files = repository.getAllFiles().asLiveData()

    val playerManager = AudioPlayerManager()

    private val _playbackState = MutableLiveData(PlaybackState())
    val playbackState: LiveData<PlaybackState> = _playbackState

    init {
        playerManager.setListener(object : AudioPlayerManager.Listener {
            override fun onStateChanged(state: PlayerState) {
                _playbackState.value = _playbackState.value?.copy(playerState = state)
            }

            override fun onPositionUpdate(positionMs: Int, durationMs: Int) {
                _playbackState.value = _playbackState.value?.copy(
                    positionMs = positionMs,
                    durationMs = durationMs
                )
            }

            override fun onPlaybackCompleted() {
                _playbackState.value = PlaybackState()
            }

            override fun onError(message: String) {
                _playbackState.value = PlaybackState()
            }
        })
    }

    fun getStorageInfo(): String {
        val available = FileUtils.getAvailableSpace(getApplication())
        return "Free: ${FileUtils.formatFileSize(available)}"
    }

    fun playFile(file: AudioFileEntity) {
        val wavPath = file.wavPath
        if (wavPath != null && File(wavPath).exists()) {
            _playbackState.value = PlaybackState(fileId = file.id)
            playerManager.play(file.id, wavPath)
        }
    }

    fun togglePlayPause(fileId: Long) {
        if (playerManager.currentPlayingFileId == fileId) {
            playerManager.togglePlayPause()
        }
    }

    fun seekTo(positionMs: Int) {
        playerManager.seekTo(positionMs)
    }

    fun stopPlayback() {
        playerManager.stop()
        _playbackState.value = PlaybackState()
    }

    fun deleteFile(file: AudioFileEntity) {
        if (playerManager.currentPlayingFileId == file.id) {
            playerManager.stop()
            _playbackState.value = PlaybackState()
        }
        viewModelScope.launch {
            repository.delete(file)
        }
    }

    fun convertToWav(file: AudioFileEntity) {
        WavConversionWorker.enqueue(getApplication(), file.id)
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
