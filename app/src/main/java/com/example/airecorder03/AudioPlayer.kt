package com.example.airecorder03

import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentlyPlayingFile = MutableStateFlow<String?>(null)
    val currentlyPlayingFile: StateFlow<String?> = _currentlyPlayingFile

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition

    fun playFile(filePath: String) {
        stop()
        val player = MediaPlayer()
        try {
            player.setDataSource(filePath)
            player.prepare()
            player.start()
            mediaPlayer = player
            _isPlaying.value = true
            _currentlyPlayingFile.value = filePath
            startProgressUpdates()
            player.setOnCompletionListener {
                stop() // Now unambiguously calls AudioPlayer.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Release the broken player without assigning it to mediaPlayer
            try { player.release() } catch (_: Exception) {}
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        stopProgressUpdates()
    }

    fun resume() {
        mediaPlayer?.start()
        _isPlaying.value = true
        startProgressUpdates()
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: IllegalStateException) {
            // MediaPlayer was not in a valid state to call stop() – safe to ignore
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _currentPosition.value = 0
        _currentlyPlayingFile.value = null
        stopProgressUpdates()
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        _currentPosition.value = position
    }

    fun forward(milliseconds: Int) {
        mediaPlayer?.let {
            val newPosition = min(it.currentPosition + milliseconds, it.duration)
            seekTo(newPosition)
        }
    }

    fun rewind(milliseconds: Int) {
        mediaPlayer?.let {
            val newPosition = max(it.currentPosition - milliseconds, 0)
            seekTo(newPosition)
        }
    }

    fun getDuration(): Int {
        return mediaPlayer?.duration ?: 0
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = GlobalScope.launch(Dispatchers.Main) {
            while (_isPlaying.value) {
                _currentPosition.value = mediaPlayer?.currentPosition ?: 0
                delay(100)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
    }
}
