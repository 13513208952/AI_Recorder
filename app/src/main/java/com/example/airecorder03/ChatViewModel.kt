package com.example.airecorder03

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isSearchResult: Boolean = false,
    val thinkingText: String? = null  // For Qwen3-Max deep thinking reasoning
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val messages = mutableStateListOf<ChatMessage>()
    var isInitializing by mutableStateOf(false)
        private set
    var isSending by mutableStateOf(false)
        private set
    var initError by mutableStateOf<String?>(null)
        private set

    // Track if AI engine is currently active
    var isEngineActive by mutableStateOf(false)
        private set

    private var autoReleaseJob: Job? = null

    // Tavily search integration (for local LLM mode)
    private val tavilyService = TavilyService()
    val apiKeyStore = ApiKeyStore(application)

    // Cloud services
    private val qwenCloudService = QwenCloudService()

    // Shared index manager — reads the same JSON the RecordingsListViewModel writes.
    private val syncManager = LocalContentSyncManager(application)

    var isWebSearchEnabled by mutableStateOf(false)
    var isSearching by mutableStateOf(false)
        private set

    /** Whether to prepend the local content sync JSON before the user's input. */
    var isFileMemoryEnabled by mutableStateOf(false)

    /**
     * Tracks whether this session has already injected the JSON when the
     * "减少本地文件记忆token消耗" option is on. Only the first user message of a
     * session receives the JSON prefix in that mode.
     */
    private var fileMemoryInjectedThisSession: Boolean = false

    // Cloud LLM conversation history (maintained separately for API calls)
    private val cloudMessageHistory = mutableListOf<QwenMessage>()

    // Current LLM provider (observable for UI updates)
    var currentLlmProvider by mutableStateOf(LlmProvider.LOCAL_LITERTLM)
        private set

    init {
        currentLlmProvider = apiKeyStore.getLlmProvider()
        // If user has a Tavily key and is in local mode, default web search to enabled
        isWebSearchEnabled = when (currentLlmProvider) {
            LlmProvider.LOCAL_LITERTLM -> apiKeyStore.hasTavilyApiKey()
            LlmProvider.CLOUD_QWEN3_MAX -> false  // Cloud has native search, starts disabled
        }
    }

    /**
     * Switch LLM provider. Clears conversation and re-initializes as needed.
     */
    fun switchLlmProvider(provider: LlmProvider) {
        if (provider == currentLlmProvider) return

        // Clean up current state
        closeEngineInternal()
        cloudMessageHistory.clear()
        messages.clear()
        fileMemoryInjectedThisSession = false

        currentLlmProvider = provider
        apiKeyStore.setLlmProvider(provider)

        // Reset search state for new provider
        isWebSearchEnabled = when (provider) {
            LlmProvider.LOCAL_LITERTLM -> apiKeyStore.hasTavilyApiKey()
            LlmProvider.CLOUD_QWEN3_MAX -> false
        }
        // File memory is cloud-only
        if (provider != LlmProvider.CLOUD_QWEN3_MAX) {
            isFileMemoryEnabled = false
        }

        // For local mode, engine will be initialized on chat resume
        if (provider == LlmProvider.CLOUD_QWEN3_MAX) {
            // Cloud mode is always "active" (no local engine needed)
            isEngineActive = apiKeyStore.hasQwenCloudApiKey()
        }
    }

    fun onChatResumed() {
        autoReleaseJob?.cancel()
        currentLlmProvider = apiKeyStore.getLlmProvider()
        
        when (currentLlmProvider) {
            LlmProvider.LOCAL_LITERTLM -> {
                if (engine == null) {
                    initEngine()
                }
            }
            LlmProvider.CLOUD_QWEN3_MAX -> {
                // Cloud mode: no engine to init, always active if API key present
                isEngineActive = apiKeyStore.hasQwenCloudApiKey()
                isInitializing = false
                initError = if (!apiKeyStore.hasQwenCloudApiKey()) "请在设置中填入 Qwen3-Max API Key" else null
            }
        }
    }

    fun onChatPaused() {
        if (currentLlmProvider == LlmProvider.LOCAL_LITERTLM) {
            autoReleaseJob?.cancel()
            autoReleaseJob = viewModelScope.launch {
                delay(90_000) // 90 seconds
                closeEngineInternal()
            }
        }
        // Cloud mode: nothing to release
    }

    fun forceCloseForExternalUse() {
        closeEngineInternal()
    }

    private fun closeEngineInternal() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        if (currentLlmProvider == LlmProvider.LOCAL_LITERTLM) {
            isEngineActive = false
        }
    }

    private fun initEngine() {
        if (isInitializing) return
        isInitializing = true
        initError = null
        viewModelScope.launch {
            try {
                val modelFile = prepareModelFile()
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU,
                    cacheDir = getApplication<Application>().cacheDir.path
                )
                
                engine = withContext(Dispatchers.IO) {
                    Engine(config).apply {
                        initialize()
                    }
                }
                
                conversation = engine?.createConversation()
                isEngineActive = true
                isInitializing = false
            } catch (e: Exception) {
                e.printStackTrace()
                initError = e.message ?: "AI 引擎初始化失败"
                isInitializing = false
                isEngineActive = false
            }
        }
    }

    private suspend fun prepareModelFile(): File = withContext(Dispatchers.IO) {
        val modelDir = File(getApplication<Application>().filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        
        val fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
        val destFile = File(modelDir, fileName)
        
        if (!destFile.exists()) {
            val assetPath = "qwen/$fileName"
            getApplication<Application>().assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        destFile
    }

    fun saveTavilyApiKey(key: String) {
        apiKeyStore.setTavilyApiKey(key)
        if (key.isNotBlank()) {
            isWebSearchEnabled = true
        }
    }

    fun sendMessage(text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty() || isSending) return

        when (currentLlmProvider) {
            LlmProvider.LOCAL_LITERTLM -> sendMessageLocal(trimmedText)
            LlmProvider.CLOUD_QWEN3_MAX -> sendMessageCloud(trimmedText)
        }
    }

    /**
     * Send message using local LiteRT-LM engine (existing behavior).
     */
    private fun sendMessageLocal(text: String) {
        if (conversation == null) return

        messages.add(ChatMessage(text, isUser = true))
        isSending = true

        viewModelScope.launch {
            val shouldSearch = isWebSearchEnabled && apiKeyStore.hasTavilyApiKey()

            var searchContext: String? = null

            // Step 1: If web search is enabled, search first via Tavily
            if (shouldSearch) {
                isSearching = true
                val searchResult = tavilyService.search(
                    query = text,
                    apiKey = apiKeyStore.getTavilyApiKey()
                )

                if (searchResult != null && searchResult.success && searchResult.results.isNotEmpty()) {
                    searchContext = tavilyService.formatSearchContext(searchResult)
                    messages.add(ChatMessage(
                        "🔍 已搜索到 ${searchResult.results.size} 条相关结果",
                        isUser = false,
                        isSearchResult = true
                    ))
                } else if (searchResult != null && !searchResult.success) {
                    messages.add(ChatMessage(
                        "⚠️ 搜索失败: ${searchResult.error}",
                        isUser = false,
                        isSearchResult = true
                    ))
                }
                isSearching = false
            }

            // Step 2: Build the prompt with or without search context
            val prompt = if (searchContext != null) {
                "以下是关于用户问题的联网搜索结果，请根据这些信息回答用户的问题。如果搜索结果与问题无关，请忽略搜索结果并直接回答。\n\n$searchContext\n\n用户问题: $text"
            } else {
                text
            }

            // Step 3: Send to LLM and stream response
            val aiMessageIndex = messages.size
            messages.add(ChatMessage("", isUser = false))

            var fullResponse = ""
            conversation?.sendMessageAsync(Message.of(prompt))
                ?.catch { e ->
                    messages[aiMessageIndex] = ChatMessage("错误: ${e.message}", isUser = false)
                    isSending = false
                }
                ?.collect { partial ->
                    fullResponse += partial.toString()
                    messages[aiMessageIndex] = ChatMessage(fullResponse, isUser = false)
                }
            isSending = false
        }
    }

    /**
     * Send message using cloud Qwen3-Max service.
     * Supports native web search (no Tavily needed) and deep thinking.
     *
     * When [isFileMemoryEnabled] is on, the local content sync JSON is prepended to
     * the user's message before being sent. If [ApiKeyStore.getReduceFileMemoryTokens]
     * is on, the JSON is only injected on the first user message of the session.
     */
    private fun sendMessageCloud(text: String) {
        if (!apiKeyStore.hasQwenCloudApiKey()) return

        messages.add(ChatMessage(text, isUser = true))
        isSending = true

        viewModelScope.launch {
            val reduceTokens = apiKeyStore.getReduceFileMemoryTokens()
            val shouldInject = isFileMemoryEnabled && (!reduceTokens || !fileMemoryInjectedThisSession)

            val effectiveContent = if (shouldInject) {
                val indexJson = syncManager.readJsonString()
                fileMemoryInjectedThisSession = true
                "以下是本软件用户当前录音文件索引（JSON，包含文件名、时长、录制时间，以及可能存在的二次摘要）。请结合这些信息回答用户的问题。如果与问题无关可以忽略。\n" +
                    "LOCAL_FILE_INDEX_JSON:\n$indexJson\n\n用户问题: $text"
            } else {
                text
            }

            cloudMessageHistory.add(QwenMessage("user", effectiveContent))
            var fullThinking = ""
            var fullContent = ""

            // Add search indicator if web search is enabled
            if (isWebSearchEnabled) {
                isSearching = true
                messages.add(ChatMessage(
                    "🔍 正在联网搜索并思考中...",
                    isUser = false,
                    isSearchResult = true
                ))
            }

            // Add AI response placeholder
            val responseIndex = messages.size
            messages.add(ChatMessage("", isUser = false))

            qwenCloudService.chat(
                messages = cloudMessageHistory,
                apiKey = apiKeyStore.getQwenCloudApiKey(),
                enableSearch = isWebSearchEnabled,
                enableThinking = true
            ).catch { e ->
                messages[responseIndex] = ChatMessage("错误: ${e.message}", isUser = false)
                isSending = false
                isSearching = false
            }.collect { delta ->
                when (delta) {
                    is QwenStreamDelta.Thinking -> {
                        fullThinking += delta.text
                        messages[responseIndex] = ChatMessage(
                            text = if (fullContent.isEmpty()) "🤔 思考中..." else fullContent,
                            isUser = false,
                            thinkingText = fullThinking
                        )
                    }
                    is QwenStreamDelta.Content -> {
                        isSearching = false  // Search phase is done once content arrives
                        fullContent += delta.text
                        messages[responseIndex] = ChatMessage(
                            text = fullContent,
                            isUser = false,
                            thinkingText = fullThinking.ifEmpty { null }
                        )
                    }
                    is QwenStreamDelta.Error -> {
                        messages[responseIndex] = ChatMessage(
                            "错误: ${delta.message}",
                            isUser = false
                        )
                    }
                }
            }

            // Add assistant response to cloud history
            if (fullContent.isNotEmpty()) {
                cloudMessageHistory.add(QwenMessage("assistant", fullContent))
            }

            isSearching = false
            isSending = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeEngineInternal()
        autoReleaseJob?.cancel()
    }
}
