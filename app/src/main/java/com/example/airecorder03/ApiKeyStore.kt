package com.example.airecorder03

import android.content.Context
import android.content.SharedPreferences

/**
 * Service provider for speech recognition.
 * LOCAL_VOSK: On-device Vosk (decode→resample→recognize pipeline)
 * CLOUD_IFLYTEK: iFlytek 语音转写 (direct file upload, no local decode)
 */
enum class SpeechProvider(val displayName: String) {
    LOCAL_VOSK("本地 (Vosk)"),
    CLOUD_IFLYTEK("云端 (讯飞语音转写)")
}

/**
 * Service provider for LLM (chat & translation).
 * LOCAL_LITERTLM: On-device LiteRT-LM with Qwen2.5-1.5B
 * CLOUD_QWEN3_MAX: Alibaba Cloud Qwen3-max-2026-01-23 (deep thinking + native web search)
 */
enum class LlmProvider(val displayName: String) {
    LOCAL_LITERTLM("本地 (LiteRT-LM)"),
    CLOUD_QWEN3_MAX("云端 (Qwen3-Max)")
}

/**
 * Manages user-provided API keys and service provider selections using SharedPreferences.
 * Provider selections default to local and persist across app restarts.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "api_key_prefs",
        Context.MODE_PRIVATE
    )

    // ============================================================
    // Tavily API Key (for local LLM web search)
    // ============================================================

    fun getTavilyApiKey(): String {
        return prefs.getString(KEY_TAVILY, "") ?: ""
    }

    fun setTavilyApiKey(key: String) {
        prefs.edit().putString(KEY_TAVILY, key.trim()).apply()
    }

    fun hasTavilyApiKey(): Boolean {
        return getTavilyApiKey().isNotBlank()
    }

    // ============================================================
    // iFlytek Speech Transcription Credentials (APPID + SecretKey)
    // ============================================================

    fun getIFlytekAppId(): String {
        return prefs.getString(KEY_IFLYTEK_APPID, "") ?: ""
    }

    fun setIFlytekAppId(appId: String) {
        prefs.edit().putString(KEY_IFLYTEK_APPID, appId.trim()).apply()
    }

    fun getIFlytekSecretKey(): String {
        return prefs.getString(KEY_IFLYTEK_SECRET, "") ?: ""
    }

    fun setIFlytekSecretKey(key: String) {
        prefs.edit().putString(KEY_IFLYTEK_SECRET, key.trim()).apply()
    }

    fun hasIFlytekCredentials(): Boolean {
        return getIFlytekAppId().isNotBlank() && getIFlytekSecretKey().isNotBlank()
    }

    // ============================================================
    // Qwen3-Max Cloud LLM API Key (DashScope)
    // ============================================================

    fun getQwenCloudApiKey(): String {
        return prefs.getString(KEY_QWEN_CLOUD, "") ?: ""
    }

    fun setQwenCloudApiKey(key: String) {
        prefs.edit().putString(KEY_QWEN_CLOUD, key.trim()).apply()
    }

    fun hasQwenCloudApiKey(): Boolean {
        return getQwenCloudApiKey().isNotBlank()
    }

    // ============================================================
    // Service Provider Selection (persisted, defaults to local)
    // ============================================================

    fun getSpeechProvider(): SpeechProvider {
        val value = prefs.getString(KEY_SPEECH_PROVIDER, SpeechProvider.LOCAL_VOSK.name)
            ?: SpeechProvider.LOCAL_VOSK.name
        return try {
            SpeechProvider.valueOf(value)
        } catch (_: Exception) {
            SpeechProvider.LOCAL_VOSK
        }
    }

    fun setSpeechProvider(provider: SpeechProvider) {
        prefs.edit().putString(KEY_SPEECH_PROVIDER, provider.name).apply()
    }

    fun getLlmProvider(): LlmProvider {
        val value = prefs.getString(KEY_LLM_PROVIDER, LlmProvider.LOCAL_LITERTLM.name)
            ?: LlmProvider.LOCAL_LITERTLM.name
        return try {
            LlmProvider.valueOf(value)
        } catch (_: Exception) {
            LlmProvider.LOCAL_LITERTLM
        }
    }

    fun setLlmProvider(provider: LlmProvider) {
        prefs.edit().putString(KEY_LLM_PROVIDER, provider.name).apply()
    }

    // ============================================================
    // Local file memory options
    // ============================================================

    /**
     * When true, only the first user message in a conversation session injects the
     * local content sync JSON. Subsequent messages skip injection to save tokens.
     */
    fun getReduceFileMemoryTokens(): Boolean {
        return prefs.getBoolean(KEY_REDUCE_FILE_MEMORY_TOKENS, false)
    }

    fun setReduceFileMemoryTokens(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_FILE_MEMORY_TOKENS, enabled).apply()
    }

    companion object {
        private const val KEY_TAVILY = "tavily_api_key"
        private const val KEY_IFLYTEK_APPID = "iflytek_app_id"
        private const val KEY_IFLYTEK_SECRET = "iflytek_secret_key"
        private const val KEY_QWEN_CLOUD = "qwen_cloud_api_key"
        private const val KEY_SPEECH_PROVIDER = "speech_provider"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_REDUCE_FILE_MEMORY_TOKENS = "reduce_file_memory_tokens"
    }
}
