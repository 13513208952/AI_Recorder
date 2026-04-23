package com.example.airecorder03

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Data class for messages sent to the Qwen3-Max cloud API.
 */
data class QwenMessage(val role: String, val content: String)

/**
 * Represents a streaming delta from the Qwen3-Max API.
 * The model supports deep thinking, so responses include both reasoning and final content.
 */
sealed class QwenStreamDelta {
    /** Thinking/reasoning process text (from reasoning_content field) */
    data class Thinking(val text: String) : QwenStreamDelta()

    /** Final response content */
    data class Content(val text: String) : QwenStreamDelta()

    /** Error during streaming */
    data class Error(val message: String) : QwenStreamDelta()
}

/**
 * Cloud LLM service using Alibaba Cloud's DashScope API with the Qwen3-Max model.
 *
 * Qwen3-max-2026-01-23 is a deep thinking model that supports:
 * - Native web search (enable_search parameter, no need for external search API)
 * - Deep reasoning (enable_thinking parameter, returns reasoning_content)
 * - Streaming responses via SSE
 *
 * API: DashScope OpenAI-compatible endpoint
 * Endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 */
class QwenCloudService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)  // Long timeout for deep thinking
        .build()

    // Separate client for Omni model with even longer timeout (audio processing)
    private val omniClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // 5 min timeout for large audio files
        .writeTimeout(120, TimeUnit.SECONDS) // 2 min write timeout for uploading audio data
        .build()

    /**
     * Send a chat message to Qwen3-Max with streaming response.
     *
     * @param messages Conversation history (role: "user"/"assistant"/"system")
     * @param apiKey DashScope API key
     * @param enableSearch Enable native web search
     * @param enableThinking Enable deep thinking/reasoning (returns reasoning_content)
     * @return Flow of streaming deltas
     */
    fun chat(
        messages: List<QwenMessage>,
        apiKey: String,
        enableSearch: Boolean = false,
        enableThinking: Boolean = true
    ): Flow<QwenStreamDelta> = flow {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val body = JSONObject().apply {
            put("model", MODEL_NAME)
            put("messages", messagesArray)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            if (enableSearch) put("enable_search", true)
            // For Qwen3-max, thinking is controlled via enable_thinking
            if (enableThinking) {
                put("enable_thinking", true)
                put("thinking_budget", 10000)
            } else {
                put("enable_thinking", false)
            }
        }

        val request = Request.Builder()
            .url(API_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val errorMsg = try {
                    val errorJson = JSONObject(errorBody)
                    errorJson.optJSONObject("error")?.optString("message") ?: errorBody
                } catch (_: Exception) {
                    "HTTP ${response.code}: $errorBody"
                }
                emit(QwenStreamDelta.Error(errorMsg))
                return@flow
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            reader?.use {
                while (true) {
                    val line = it.readLine() ?: break
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue

                        // Qwen3-max returns reasoning_content for thinking and content for response
                        // Note: Android's org.json optString returns "null" string when value is JSON null
                        val thinkingContent = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                        val responseContent = if (delta.isNull("content")) "" else delta.optString("content", "")

                        if (thinkingContent.isNotEmpty()) {
                            emit(QwenStreamDelta.Thinking(thinkingContent))
                        }
                        if (responseContent.isNotEmpty()) {
                            emit(QwenStreamDelta.Content(responseContent))
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE lines
                    }
                }
            }
        } catch (e: Exception) {
            emit(QwenStreamDelta.Error(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Simple non-streaming translation call.
     * Uses Qwen3-Max without thinking for faster translation.
     *
     * @return Flow of content deltas (no thinking, for faster response)
     */
    fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String
    ): Flow<QwenStreamDelta> {
        val prompt = if (targetLanguage == "zh") {
            "请将以下内容翻译为流畅通顺的现代简体中文白话文。仅返回翻译结果，不要包含原文或任何解释：\n$text"
        } else {
            "Please translate the following text into fluent English. Return only the translation, no explanation:\n$text"
        }

        val messages = listOf(
            QwenMessage("system", "你是一个专业的翻译助手。只输出翻译结果，不输出任何其他内容。"),
            QwenMessage("user", prompt)
        )

        return chat(
            messages = messages,
            apiKey = apiKey,
            enableSearch = false,
            enableThinking = false  // Disable thinking for faster translation
        )
    }

    /**
     * Summarize an audio file using Qwen 3.5 Omni Plus.
     * This model natively understands audio (speech, music, environment sounds)
     * without requiring transcription first.
     *
     * @param audioFilePath Path to the audio file on disk
     * @param apiKey DashScope API key (same key as Qwen3-Max)
     * @param prompt Custom prompt for summarization
     * @return Flow of streaming content deltas
     */
    fun summarizeAudio(
        audioFilePath: String,
        apiKey: String,
        prompt: String = "请仔细聆听这段音频，然后用中文对其内容进行详细的总结。如果是语音对话，请概括主要讨论的话题和要点；如果是音乐，请描述音乐的风格、情感和特点；如果包含其他声音，请描述你听到的内容。"
    ): Flow<QwenStreamDelta> = flow {
        val file = File(audioFilePath)
        if (!file.exists()) {
            emit(QwenStreamDelta.Error("音频文件不存在: $audioFilePath"))
            return@flow
        }

        // Check file size (20MB limit for base64 inline upload)
        val fileSizeMB = file.length() / (1024.0 * 1024.0)
        if (fileSizeMB > 20) {
            emit(QwenStreamDelta.Error("音频文件过大 (${String.format(Locale.US, "%.1f", fileSizeMB)} MB)，最大支持 20 MB"))
            return@flow
        }

        // Check audio duration (max 30 minutes — model processing limit)
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFilePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            val durationMinutes = durationMs / 60000.0
            if (durationMinutes > 30) {
                emit(QwenStreamDelta.Error("音频时长超出限制 (${String.format(Locale.US, "%.0f", durationMinutes)} 分钟)，最大支持 30 分钟"))
                return@flow
            }
        } catch (_: Exception) {
            // If duration retrieval fails, proceed anyway — API will reject if too long
        }

        // Determine audio format from file extension
        val format = when (file.extension.lowercase()) {
            "mp3" -> "mp3"
            "wav" -> "wav"
            "m4a" -> "m4a"
            "flac" -> "flac"
            "aac" -> "aac"
            "ogg" -> "ogg"
            else -> "mp3"
        }

        // Base64 encode the audio file
        val audioBytes = file.readBytes()
        val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)

        // Build multimodal content array
        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "input_audio")
                put("input_audio", JSONObject().apply {
                    put("data", "data:audio/$format;base64,$base64Audio")
                    put("format", format)
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        val body = JSONObject().apply {
            put("model", OMNI_MODEL_NAME)
            put("messages", messagesArray)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            // Only text output, no audio output
            put("modalities", JSONArray().apply { put("text") })
        }

        val request = Request.Builder()
            .url(API_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = omniClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val errorMsg = try {
                    val errorJson = JSONObject(errorBody)
                    errorJson.optJSONObject("error")?.optString("message") ?: errorBody
                } catch (_: Exception) {
                    "HTTP ${response.code}: $errorBody"
                }
                emit(QwenStreamDelta.Error(errorMsg))
                return@flow
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            reader?.use {
                while (true) {
                    val line = it.readLine() ?: break
                    if (!line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue

                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta") ?: continue

                        val responseContent = if (delta.isNull("content")) "" else delta.optString("content", "")

                        if (responseContent.isNotEmpty()) {
                            emit(QwenStreamDelta.Content(responseContent))
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE lines
                    }
                }
            }
        } catch (e: Exception) {
            emit(QwenStreamDelta.Error(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val API_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val MODEL_NAME = "qwen3-max-2026-01-23"
        private const val OMNI_MODEL_NAME = "qwen3.5-omni-plus"
    }
}
