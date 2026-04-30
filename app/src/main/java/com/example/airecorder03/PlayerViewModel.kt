package com.example.airecorder03

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.airecorder03.database.RecordingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlayerViewModel() : ViewModel() {

    private val audioPlayer = AudioPlayer()

    private val _currentlyPlayingItem = MutableStateFlow<RecordingItem?>(null)
    val currentlyPlayingItem: StateFlow<RecordingItem?> = _currentlyPlayingItem

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Int> = audioPlayer.currentPosition
    val duration: Int
        get() = audioPlayer.getDuration()

    fun playRecording(recording: RecordingItem) {
        if (_currentlyPlayingItem.value?.id == recording.id && isPlaying.value) {
            audioPlayer.pause()
        } else if (_currentlyPlayingItem.value?.id == recording.id && !isPlaying.value) {
            audioPlayer.resume()
        } else {
            _currentlyPlayingItem.value = recording
            audioPlayer.playFile(recording.filePath)
        }
    }

    fun playPause() {
        if (isPlaying.value) {
            audioPlayer.pause()
        } else {
            if (_currentlyPlayingItem.value != null) {
                audioPlayer.resume()
            }
        }
    }

    fun seekTo(position: Int) {
        audioPlayer.seekTo(position)
    }

    fun forward() {
        audioPlayer.forward(200)
    }

    fun rewind() {
        audioPlayer.rewind(200)
    }

    fun stopPlayback() {
        _currentlyPlayingItem.value = null
        audioPlayer.stop()
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }

    class Factory() : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PlayerViewModel() as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
