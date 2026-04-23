package com.example.airecorder03

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.airecorder03.database.AppDatabase
import com.example.airecorder03.database.RecordingItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    var isRecording by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var recordingDuration by mutableStateOf("00:00")
        private set

    private val audioRecorder: Recorder
    private val recordingDao = AppDatabase.getDatabase(application).recordingDao()

    private var timerJob: Job? = null
    private var recordingStartTime = 0L
    private var timePaused = 0L
    private var currentOutputFile: File? = null

    init {
        audioRecorder = Recorder(application.applicationContext)
    }

    fun startRecording() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "rec_$timeStamp.m4a"
        val storageDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs()
        }
        currentOutputFile = File(storageDir, fileName)

        currentOutputFile?.let {
            audioRecorder.start(it)
            isRecording = true
            isPaused = false
            recordingStartTime = System.currentTimeMillis()
            startTimer()
        }
    }

    fun stopRecording() {
        audioRecorder.stop()

        currentOutputFile?.let {
            val newItem = RecordingItem(
                fileName = it.name,
                filePath = it.absolutePath,
                timestamp = System.currentTimeMillis(),
                duration = recordingDuration
            )
            viewModelScope.launch {
                recordingDao.insert(newItem)
            }
        }

        isRecording = false
        isPaused = false
        stopTimer()
        recordingDuration = "00:00"
        currentOutputFile = null
    }

    fun pauseRecording() {
        audioRecorder.pause()
        isPaused = true
        timePaused = System.currentTimeMillis()
        stopTimer()
    }

    fun resumeRecording() {
        audioRecorder.resume()
        isPaused = false
        recordingStartTime += (System.currentTimeMillis() - timePaused)
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isRecording) {
                val elapsedMillis = System.currentTimeMillis() - recordingStartTime
                recordingDuration = formatDuration(elapsedMillis)
                delay(500) // Update twice a second
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isRecording) {
            audioRecorder.stop()
        }
    }
}
