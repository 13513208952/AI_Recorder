package com.example.airecorder03

import android.app.Application
import android.media.MediaPlayer
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

data class Sentence(
    val text: String,
    val start: Double,
    val end: Double,
    val translation: String? = null
)

class TranscriptionViewerViewModel(
    private val application: Application,
    private var recordingPath: String?,
    private var transcriptionPath: String?
) : AndroidViewModel(application) {

    private val _sentences = MutableStateFlow<List<Sentence>>(emptyList())
    val sentences: StateFlow<List<Sentence>> = _sentences.asStateFlow()

    private val _currentSentenceIndex = MutableStateFlow(-1)
    val currentSentenceIndex: StateFlow<Int> = _currentSentenceIndex.asStateFlow()

    private val _isAutoScrollEnabled = MutableStateFlow(true)
    val isAutoScrollEnabled: StateFlow<Boolean> = _isAutoScrollEnabled.asStateFlow()

    private val _scrollRequest = MutableSharedFlow<Int>()
    val scrollRequest = _scrollRequest.asSharedFlow()
    
    private val _closeScreenEvent = MutableSharedFlow<Unit>()
    val closeScreenEvent = _closeScreenEvent.asSharedFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0)
    val duration: StateFlow<Int> = _duration.asStateFlow()

    private var timerJob: Job? = null
    
    // Local LiteRT-LM engine (for local translation)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // Cloud service and settings
    private val qwenCloudService = QwenCloudService()
    private val apiKeyStore = ApiKeyStore(application)

    init {
        loadTranscription()
        preparePlayer()
    }

    fun loadRecording(newRecordingPath: String?, newTranscriptionPath: String?) {
        val pathChanged = this.recordingPath != newRecordingPath || this.transcriptionPath != newTranscriptionPath

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        stopTimer()
        
        this.recordingPath = newRecordingPath
        this.transcriptionPath = newTranscriptionPath
        _sentences.value = emptyList()
        _currentPosition.value = 0
        _duration.value = 0
        _isTranslating.value = false
        _currentSentenceIndex.value = -1

        loadTranscription()
        if (pathChanged) {
            preparePlayer()
        }
    }

    private fun loadTranscription() {
        val path = transcriptionPath ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val jsonString = file.readText()
                        val result = Json.decodeFromString<VoskTranscriptionResult>(jsonString)
                        _sentences.value = result.words.let { groupWordsIntoSentences(it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun groupWordsIntoSentences(words: List<Word>): List<Sentence> {
        val sentences = mutableListOf<Sentence>()
        var currentSentenceText = ""
        var sentenceStart = -1.0

        words.forEachIndexed { index, word ->
            if (sentenceStart < 0) {
                sentenceStart = word.start
            }
            currentSentenceText += " ${word.text}"

            val isLastWord = index == words.size - 1
            val pauseDuration = if (!isLastWord) words[index + 1].start - word.end else 0.0

            if (pauseDuration > 0.7 || isLastWord) {
                sentences.add(Sentence(currentSentenceText.trim(), sentenceStart, word.end))
                currentSentenceText = ""
                sentenceStart = -1.0
            }
        }
        return sentences
    }

    private fun preparePlayer() {
        val path = recordingPath ?: return
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(application, path.toUri())
                prepare()
                _duration.value = duration
                setOnCompletionListener { stopPlayback() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Check if cloud translation is available (no need to close ChatViewModel's engine).
     */
    fun isCloudLlmMode(): Boolean {
        return apiKeyStore.getLlmProvider() == LlmProvider.CLOUD_QWEN3_MAX
                && apiKeyStore.hasQwenCloudApiKey()
    }

    /**
     * Translate all sentences using the currently selected LLM provider.
     * LOCAL_LITERTLM: Uses on-device engine (requires closing ChatViewModel's engine first)
     * CLOUD_QWEN3_MAX: Uses cloud API (no local engine conflict)
     */
    fun translateAll() {
        if (_isTranslating.value || _sentences.value.isEmpty()) return
        
        when (apiKeyStore.getLlmProvider()) {
            LlmProvider.LOCAL_LITERTLM -> translateAllLocal()
            LlmProvider.CLOUD_QWEN3_MAX -> translateAllCloud()
        }
    }

    /**
     * Local translation using LiteRT-LM engine (existing behavior).
     */
    private fun translateAllLocal() {
        viewModelScope.launch {
            _isTranslating.value = true
            try {
                if (engine == null) {
                    val modelFile = prepareModelFile()
                    val config = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.GPU,
                        cacheDir = application.cacheDir.path
                    )
                    engine = withContext(Dispatchers.IO) {
                        Engine(config).apply { initialize() }
                    }
                    conversation = engine?.createConversation()
                }

                val currentList = _sentences.value.toMutableList()
                for (i in currentList.indices) {
                    val original = currentList[i]
                    if (original.translation != null) continue

                    val prompt = "请将以下内容翻译为流畅通顺的现代简体中文白话文。仅返回翻译结果，不要包含原文或任何解释：\n${original.text}"
                    
                    var translatedText = ""
                    conversation?.sendMessageAsync(Message.of(prompt))
                        ?.catch { e -> translatedText = "翻译失败: ${e.message}" }
                        ?.collect { partial ->
                            translatedText += partial.toString()
                            currentList[i] = original.copy(translation = translatedText)
                            _sentences.value = currentList.toList()
                        }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTranslating.value = false
            }
        }
    }

    /**
     * Cloud translation using Qwen3-Max API.
     * No local engine needed — no conflict with ChatViewModel's engine.
     */
    private fun translateAllCloud() {
        val apiKey = apiKeyStore.getQwenCloudApiKey()
        if (apiKey.isBlank()) return

        viewModelScope.launch {
            _isTranslating.value = true
            try {
                val currentList = _sentences.value.toMutableList()
                for (i in currentList.indices) {
                    val original = currentList[i]
                    if (original.translation != null) continue

                    var translatedText = ""
                    qwenCloudService.translate(
                        text = original.text,
                        targetLanguage = "zh",
                        apiKey = apiKey
                    ).catch { e ->
                        translatedText = "翻译失败: ${e.message}"
                    }.collect { delta ->
                        when (delta) {
                            is QwenStreamDelta.Content -> {
                                translatedText += delta.text
                                currentList[i] = original.copy(translation = translatedText)
                                _sentences.value = currentList.toList()
                            }
                            is QwenStreamDelta.Error -> {
                                translatedText = "翻译失败: ${delta.message}"
                                currentList[i] = original.copy(translation = translatedText)
                                _sentences.value = currentList.toList()
                            }
                            is QwenStreamDelta.Thinking -> {
                                // Ignore thinking for translation
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTranslating.value = false
            }
        }
    }

    private suspend fun prepareModelFile(): File = withContext(Dispatchers.IO) {
        val modelDir = File(application.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        val fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
        val destFile = File(modelDir, fileName)
        if (!destFile.exists()) {
            val assetPath = "qwen/$fileName"
            application.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        }
        destFile
    }

    fun togglePlayback() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            _isPlaying.value = false
            stopTimer()
        } else {
            mediaPlayer?.start()
            _isPlaying.value = true
            startTimer()
        }
    }

    fun toggleAutoScroll() {
        _isAutoScrollEnabled.value = !_isAutoScrollEnabled.value
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        _currentPosition.value = position
    }

    fun step(amount: Int) {
        val newPosition = (mediaPlayer?.currentPosition ?: 0) + amount
        seekTo(newPosition.coerceIn(0, mediaPlayer?.duration ?: 0))
    }
    
    fun deleteTranscription() {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptionPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
                _closeScreenEvent.emit(Unit)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_isPlaying.value) {
                val position = mediaPlayer?.currentPosition ?: 0
                _currentPosition.value = position
                updateCurrentSentenceIndex(position / 1000.0)
                delay(100)
            }
        }
    }

    private fun updateCurrentSentenceIndex(currentSeconds: Double) {
        val index = _sentences.value.indexOfFirst { currentSeconds >= it.start && currentSeconds <= it.end }
        if (index != -1 && index != _currentSentenceIndex.value) {
            _currentSentenceIndex.value = index
            if (_isAutoScrollEnabled.value) {
                viewModelScope.launch {
                    _scrollRequest.emit(index)
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    private fun stopPlayback() {
        _isPlaying.value = false
        stopTimer()
        _currentPosition.value = 0
        _currentSentenceIndex.value = -1
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
        conversation?.close()
        engine?.close()
        stopTimer()
    }
}
