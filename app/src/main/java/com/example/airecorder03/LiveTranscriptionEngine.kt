package com.example.airecorder03

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LiveTranscriptionState(
    val finalText: String = "",
    val partialText: String = "",
    val isModelLoading: Boolean = false,
    val isActive: Boolean = false,
    val language: String = "CN"
)

class LiveTranscriptionEngine(private val context: Context) {

    private val _state = MutableStateFlow(LiveTranscriptionState())
    val state: StateFlow<LiveTranscriptionState> = _state

    private var recognizer: Recognizer? = null
    @Volatile private var active = false

    suspend fun start(language: String): Boolean {
        _state.value = LiveTranscriptionState(isModelLoading = true, language = language)
        return withContext(Dispatchers.IO) {
            try {
                val model = loadModel(language)
                recognizer = Recognizer(model, PcmAudioEngine.SAMPLE_RATE.toFloat())
                active = true
                _state.value = LiveTranscriptionState(isActive = true, language = language)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = LiveTranscriptionState(language = language)
                false
            }
        }
    }

    fun processPcm(data: ByteArray, size: Int) {
        if (!active) return
        val rec = recognizer ?: return
        try {
            val accepted = rec.acceptWaveForm(data, size)
            val cur = _state.value
            if (accepted) {
                val text = parseText(rec.result)
                if (text.isNotBlank()) {
                    _state.value = cur.copy(
                        finalText = if (cur.finalText.isEmpty()) text else "${cur.finalText}\n$text",
                        partialText = ""
                    )
                }
            } else {
                val partial = parsePartial(rec.partialResult)
                _state.value = cur.copy(partialText = partial)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        active = false
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        val cur = _state.value
        _state.value = cur.copy(isActive = false, partialText = "")
    }

    fun reset() {
        stop()
        _state.value = LiveTranscriptionState()
    }

    private suspend fun loadModel(language: String): Model = suspendCancellableCoroutine { cont ->
        val name = if (language == "CN") "vosk-model-small-cn" else "vosk-model-small-en-us"
        StorageService.unpack(context, name, "model",
            { model -> cont.resume(model) },
            { ex -> cont.resumeWithException(ex) })
    }

    private fun parseText(json: String?): String =
        try { JSONObject(json ?: "{}").optString("text", "") } catch (_: Exception) { "" }

    private fun parsePartial(json: String?): String =
        try { JSONObject(json ?: "{}").optString("partial", "") } catch (_: Exception) { "" }
}
