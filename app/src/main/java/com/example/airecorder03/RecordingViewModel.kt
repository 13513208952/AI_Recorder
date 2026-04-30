package com.example.airecorder03

import android.app.Application
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.airecorder03.database.AppDatabase
import com.example.airecorder03.database.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    // ─── Private infra (declared first – other properties reference these) ───
    private val recordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val pcmEngine = PcmAudioEngine()
    private val aacEncoder = AacEncoder()
    private val replayBuffer = InstantReplayBuffer()
    private val transcriptionEngine = LiveTranscriptionEngine(application.applicationContext)

    // ─── Recording state ─────────────────────────────────────────────────────
    var isRecording by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var recordingDuration by mutableStateOf("00:00")
        private set

    // ─── Instant replay state ─────────────────────────────────────────────────
    var isSavingReplay by mutableStateOf(false)
        private set
    val recentReplays = mutableStateListOf<RecordingItem>()

    // ─── Live transcription ───────────────────────────────────────────────────
    var transcriptionLanguage by mutableStateOf("CN")
        private set
    val transcriptionState = transcriptionEngine.state

    // ─── Timer internals ──────────────────────────────────────────────────────
    private var timerJob: Job? = null
    private var recordingStartTime = 0L
    private var pausedElapsed = 0L
    private var pauseStart = 0L
    private var currentOutputFile: File? = null

    init {
        pcmEngine.addListener(object : PcmAudioEngine.PcmListener {
            override fun onPcmChunk(data: ByteArray, size: Int) { aacEncoder.encode(data, size) }
        })
        pcmEngine.addListener(object : PcmAudioEngine.PcmListener {
            override fun onPcmChunk(data: ByteArray, size: Int) { replayBuffer.write(data, size) }
        })
        pcmEngine.addListener(object : PcmAudioEngine.PcmListener {
            override fun onPcmChunk(data: ByteArray, size: Int) {
                transcriptionEngine.processPcm(data, size)
            }
        })
    }

    // ─── Recording control ────────────────────────────────────────────────────
    fun startRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
            ?: return
        dir.mkdirs()
        val file = File(dir, "rec_$stamp.m4a")
        currentOutputFile = file

        replayBuffer.reset()
        transcriptionEngine.reset()
        recentReplays.clear()

        aacEncoder.start(file)
        pcmEngine.start(viewModelScope)

        isRecording = true
        isPaused = false
        pausedElapsed = 0L
        recordingStartTime = System.currentTimeMillis()
        startTimer()
    }

    fun stopRecording() {
        val file = currentOutputFile ?: return
        isRecording = false
        isPaused = false
        stopTimer()
        recordingDuration = "00:00"
        currentOutputFile = null

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                pcmEngine.stop()
                val durationMs = aacEncoder.stop()
                transcriptionEngine.stop()

                recordingDao.insert(
                    RecordingItem(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        duration = formatDuration(durationMs)
                    )
                )
            }
        }
    }

    fun pauseRecording() {
        pcmEngine.pause()
        isPaused = true
        pauseStart = System.currentTimeMillis()
        stopTimer()
    }

    fun resumeRecording() {
        pcmEngine.resume()
        isPaused = false
        pausedElapsed += System.currentTimeMillis() - pauseStart
        startTimer()
    }

    // ─── Instant replay ───────────────────────────────────────────────────────
    fun triggerInstantReplay() {
        if (isSavingReplay || !isRecording) return
        isSavingReplay = true
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
            ?: run { isSavingReplay = false; return }

        viewModelScope.launch {
            try {
                val clip = withContext(Dispatchers.IO) { replayBuffer.saveClip(dir) }
                    ?: return@launch
                val item = RecordingItem(
                    fileName = clip.file.name,
                    filePath = clip.file.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    duration = formatDuration(clip.durationMs)
                )
                val id = withContext(Dispatchers.IO) { recordingDao.insert(item) }
                val saved = item.copy(id = id)
                recentReplays.add(0, saved)
                if (recentReplays.size > 3) recentReplays.removeAt(recentReplays.size - 1)
            } finally {
                isSavingReplay = false
            }
        }
    }

    // ─── Live transcription ───────────────────────────────────────────────────
    fun startLiveTranscription(language: String) {
        transcriptionLanguage = language
        viewModelScope.launch { transcriptionEngine.start(language) }
    }

    fun stopLiveTranscription() {
        transcriptionEngine.stop()
    }

    fun changeTranscriptionLanguage(lang: String) {
        if (!transcriptionState.value.isActive) transcriptionLanguage = lang
    }

    // ─── Timer ────────────────────────────────────────────────────────────────
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isRecording && !isPaused) {
                val elapsed = System.currentTimeMillis() - recordingStartTime - pausedElapsed
                recordingDuration = formatDuration(elapsed)
                delay(500)
            }
        }
    }

    private fun stopTimer() { timerJob?.cancel() }

    private fun formatDuration(millis: Long): String {
        val s = (millis / 1000) % 60
        val m = (millis / 60_000) % 60
        val h = millis / 3_600_000
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    override fun onCleared() {
        super.onCleared()
        pcmEngine.stopSync()
        try { aacEncoder.stop() } catch (_: Exception) {}
        transcriptionEngine.stop()
    }
}
