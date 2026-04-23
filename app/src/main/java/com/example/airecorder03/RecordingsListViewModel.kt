package com.example.airecorder03

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.airecorder03.database.AppDatabase
import com.example.airecorder03.database.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecordingsListViewModel(application: Application) : AndroidViewModel(application) {

    private val recordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val voskService = VoskService(application)
    private val iflytekSpeechService = IFlytekSpeechService()
    private val apiKeyStore = ApiKeyStore(application)
    private val qwenCloudService = QwenCloudService()
    private val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val syncManager = LocalContentSyncManager(application)

    var transcribingFileId by mutableStateOf<Long?>(null)
        private set
    var transcriptionCompleted by mutableStateOf<Boolean?>(null)
        private set

    // AI Summarization state (single-item UI dialog)
    var summarizingFileId by mutableStateOf<Long?>(null)
        private set
    var summaryProgress by mutableStateOf("")
        private set
    var summaryCompleted by mutableStateOf<Boolean?>(null)
        private set
    var summaryError by mutableStateOf<String?>(null)
        private set

    // Local content sync state
    var isSyncing by mutableStateOf(false)
        private set
    var syncStatusMessage by mutableStateOf<String?>(null)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var syncCompletedAt by mutableStateOf<Long?>(null)
        private set

    val recordings: StateFlow<List<RecordingItem>> = recordingDao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        createNotificationChannel()

        // Auto-refresh the JSON index whenever the recordings list changes.
        recordings
            .onEach { syncManager.refresh(it) }
            .launchIn(viewModelScope)

        // Periodic forced refresh every 10 minutes.
        viewModelScope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(10))
                syncManager.refresh(recordings.value)
            }
        }
    }

    /** Exposed so chat can read the JSON payload for "local file memory" injection. */
    suspend fun readSyncIndexJson(): String = syncManager.readJsonString()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Transcription"
            val descriptionText = "Notifications for transcription status"
            val importance = NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound
            val channel = NotificationChannel(TRANSCRIPTION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showTranscriptionInProgressNotification(fileName: String) {
        val notification = NotificationCompat.Builder(getApplication(), TRANSCRIPTION_CHANNEL_ID)
            .setContentTitle("Transcription in Progress")
            .setContentText("Transcribing \"$fileName\"...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a real icon
            .setOngoing(true)
            .setProgress(0, 0, true) // Indeterminate progress
            .build()
        notificationManager.notify(TRANSCRIPTION_NOTIFICATION_ID, notification)
    }

    private fun showTranscriptionCompleteNotification(fileName: String, success: Boolean) {
        notificationManager.cancel(TRANSCRIPTION_NOTIFICATION_ID) // Dismiss the progress notification
        val notification = NotificationCompat.Builder(getApplication(), TRANSCRIPTION_CHANNEL_ID)
            .setContentTitle(if (success) "Transcription Complete" else "Transcription Failed")
            .setContentText(if (success) "\"$fileName\" has been transcribed." else "Failed to transcribe \"$fileName\".")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a real icon
            .build()
        notificationManager.notify(TRANSCRIPTION_COMPLETE_NOTIFICATION_ID, notification)
    }

    /**
     * Transcribe a recording file using the currently selected speech provider.
     */
    fun transcribeFile(recording: RecordingItem, language: String) {
        viewModelScope.launch {
            transcribingFileId = recording.id
            transcriptionCompleted = null
            showTranscriptionInProgressNotification(recording.fileName)

            val speechProvider = apiKeyStore.getSpeechProvider()
            val result: VoskTranscriptionResult? = when (speechProvider) {
                SpeechProvider.LOCAL_VOSK -> {
                    voskService.transcribe(recording.filePath, language)
                }
                SpeechProvider.CLOUD_IFLYTEK -> {
                    val appId = apiKeyStore.getIFlytekAppId()
                    val secretKey = apiKeyStore.getIFlytekSecretKey()
                    if (appId.isBlank() || secretKey.isBlank()) {
                        null
                    } else {
                        iflytekSpeechService.transcribe(recording.filePath, language, appId, secretKey)
                    }
                }
            }

            if (result != null) {
                val transcriptionFile = saveTranscription(recording, result)
                val updatedRecording = recording.copy(transcriptionPath = transcriptionFile.absolutePath)
                recordingDao.update(updatedRecording)
                showTranscriptionCompleteNotification(recording.fileName, true)
                transcriptionCompleted = true
            } else {
                showTranscriptionCompleteNotification(recording.fileName, false)
                transcriptionCompleted = false
            }
        }
    }

    fun dismissTranscriptionDialog() {
        transcribingFileId = null
        transcriptionCompleted = null
    }

    /**
     * AI summarize a recording using Qwen 3.5 Omni Plus (single-item, UI-driven).
     * Shows a status dialog via the `summarizingFileId`/etc. states.
     */
    fun summarizeRecording(recording: RecordingItem) {
        val apiKey = apiKeyStore.getQwenCloudApiKey()
        if (apiKey.isBlank()) {
            summaryError = "请先在设置中填入 DashScope API Key"
            return
        }

        viewModelScope.launch {
            summarizingFileId = recording.id
            summaryCompleted = null
            summaryProgress = ""
            summaryError = null

            // Gate: must have no existing summary and file must be <=20MB.
            val latest = recordingDao.getRecordingById(recording.id)
            if (latest == null) {
                summaryError = "文件不存在"
                summaryCompleted = false
                return@launch
            }
            if (!latest.summaryText.isNullOrBlank()) {
                summaryError = "该文件已有总结，无需重复生成"
                summaryCompleted = false
                return@launch
            }
            val audioFile = File(latest.filePath)
            if (!audioFile.exists()) {
                summaryError = "音频文件不存在"
                summaryCompleted = false
                return@launch
            }
            if (audioFile.length() > LocalContentSyncManager.MAX_AUDIO_SIZE_BYTES) {
                summaryError = "音频文件超过 20MB，无法总结"
                summaryCompleted = false
                return@launch
            }

            val fullContent = StringBuilder()

            qwenCloudService.summarizeAudio(
                audioFilePath = latest.filePath,
                apiKey = apiKey
            ).catch { e ->
                summaryError = e.message ?: "总结失败"
                summaryCompleted = false
            }.collect { delta ->
                when (delta) {
                    is QwenStreamDelta.Content -> {
                        fullContent.append(delta.text)
                        summaryProgress = fullContent.toString()
                    }
                    is QwenStreamDelta.Thinking -> { /* omni doesn't stream thinking */ }
                    is QwenStreamDelta.Error -> {
                        summaryError = delta.message
                        summaryCompleted = false
                    }
                }
            }

            if (summaryCompleted == null) {
                val summaryText = fullContent.toString()
                if (summaryText.isNotBlank()) {
                    val updatedRecording = latest.copy(summaryText = summaryText)
                    recordingDao.update(updatedRecording)
                    summaryCompleted = true
                } else {
                    summaryError = "未生成任何总结内容"
                    summaryCompleted = false
                }
            }
        }
    }

    fun dismissSummaryDialog() {
        summarizingFileId = null
        summaryCompleted = null
        summaryProgress = ""
        summaryError = null
    }

    /**
     * Update the summary text for a recording (user edits).
     */
    fun updateSummary(recording: RecordingItem, newSummaryText: String) {
        viewModelScope.launch {
            val updatedRecording = recording.copy(summaryText = newSummaryText.ifBlank { null })
            recordingDao.update(updatedRecording)
        }
    }

    // ============================================================
    // Local content sync: parallel first-pass + second-pass summaries
    // ============================================================

    /**
     * Run one full local content sync:
     *   1. Refresh the JSON index.
     *   2. For every file with no first summary AND size ≤20MB, fire off a first
     *      summary in parallel (independent coroutines so one failure doesn't kill
     *      the rest).
     *   3. After all first-pass tasks settle, generate a ≤125-char second summary
     *      (plain-text Qwen3-Max call) for every file that has a first summary but
     *      no second summary AND size ≤20MB.
     *   4. Refresh the JSON index again.
     *
     * Every summary attempt re-reads the recording from the DB and re-checks the
     * ≤20MB bound and the "no existing summary" precondition right before firing.
     */
    fun performLocalContentSync() {
        if (isSyncing) return
        val apiKey = apiKeyStore.getQwenCloudApiKey()
        if (apiKey.isBlank()) {
            syncError = "请先在设置中填入 DashScope API Key"
            return
        }

        viewModelScope.launch {
            isSyncing = true
            syncError = null
            syncStatusMessage = "正在刷新索引..."

            // Always start from a fresh index.
            syncManager.refresh(recordings.value)

            // -------- First pass: audio → first summary (parallel) --------
            val firstPassCandidates = recordingDao.getAllRecordingsOnce().filter { rec ->
                rec.summaryText.isNullOrBlank() && isFileEligibleForSummary(rec.filePath)
            }
            syncStatusMessage = "第一次总结：${firstPassCandidates.size} 个文件"

            val firstPassResults = firstPassCandidates.map { rec ->
                async(Dispatchers.IO) {
                    runFirstSummarySafe(rec.id, apiKey)
                }
            }.awaitAll()

            val firstPassSucceeded = firstPassResults.count { it }

            // -------- Second pass: first summary → 125-char second summary --------
            val secondPassCandidates = recordingDao.getAllRecordingsOnce().filter { rec ->
                !rec.summaryText.isNullOrBlank() &&
                    rec.secondSummaryText.isNullOrBlank() &&
                    isFileEligibleForSummary(rec.filePath)
            }
            syncStatusMessage = "第二次总结：${secondPassCandidates.size} 个文件"

            val secondPassResults = secondPassCandidates.map { rec ->
                async(Dispatchers.IO) {
                    runSecondSummarySafe(rec.id, apiKey)
                }
            }.awaitAll()

            val secondPassSucceeded = secondPassResults.count { it }

            // Final index refresh now that second summaries are in.
            syncManager.refresh(recordings.value)

            syncStatusMessage = "完成。第一次成功 $firstPassSucceeded/${firstPassCandidates.size}，第二次成功 $secondPassSucceeded/${secondPassCandidates.size}"
            syncCompletedAt = System.currentTimeMillis()
            isSyncing = false
        }
    }

    fun dismissSyncStatus() {
        syncStatusMessage = null
        syncError = null
        syncCompletedAt = null
    }

    /**
     * Force-rebuild the content sync database.
     *   - Always: clear every second summary.
     *   - If [alsoClearFirstSummaries]: also clear every first summary.
     *   - Then trigger a full local content sync.
     */
    fun rebuildContentSync(alsoClearFirstSummaries: Boolean) {
        viewModelScope.launch {
            recordingDao.clearAllSecondSummaries()
            if (alsoClearFirstSummaries) {
                recordingDao.clearAllFirstSummaries()
            }
            performLocalContentSync()
        }
    }

    /** True iff the file exists and is under the 20MB bound. */
    private fun isFileEligibleForSummary(path: String): Boolean {
        val f = File(path)
        return f.exists() && f.length() <= LocalContentSyncManager.MAX_AUDIO_SIZE_BYTES
    }

    /**
     * Run a first-summary call for one recording. Returns true if a new summary was
     * written. Re-verifies the ≤20MB gate and "no existing summary" right before
     * firing to guard against races.
     */
    private suspend fun runFirstSummarySafe(recordingId: Long, apiKey: String): Boolean {
        val rec = recordingDao.getRecordingById(recordingId) ?: return false
        if (!rec.summaryText.isNullOrBlank()) return false
        if (!isFileEligibleForSummary(rec.filePath)) return false

        val result = StringBuilder()
        var failed = false
        try {
            qwenCloudService.summarizeAudio(
                audioFilePath = rec.filePath,
                apiKey = apiKey
            ).catch { _ -> failed = true }
             .collect { delta ->
                when (delta) {
                    is QwenStreamDelta.Content -> result.append(delta.text)
                    is QwenStreamDelta.Error -> failed = true
                    is QwenStreamDelta.Thinking -> { /* ignore */ }
                }
            }
        } catch (_: Exception) {
            failed = true
        }
        if (failed) return false
        val text = result.toString().trim()
        if (text.isBlank()) return false

        // Re-verify after the network round-trip: maybe the user just set a summary
        // manually, or the recording was deleted. Don't overwrite.
        val fresh = recordingDao.getRecordingById(recordingId) ?: return false
        if (!fresh.summaryText.isNullOrBlank()) return false
        recordingDao.update(fresh.copy(summaryText = text))
        return true
    }

    /**
     * Run a second-summary (≤125 chars) call for one recording. Returns true iff a
     * new second summary was written.
     */
    private suspend fun runSecondSummarySafe(recordingId: Long, apiKey: String): Boolean {
        val rec = recordingDao.getRecordingById(recordingId) ?: return false
        val firstSummary = rec.summaryText
        if (firstSummary.isNullOrBlank()) return false
        if (!rec.secondSummaryText.isNullOrBlank()) return false
        if (!isFileEligibleForSummary(rec.filePath)) return false

        val messages = listOf(
            QwenMessage(
                "system",
                "你是一个极简摘要助手。你只输出中文摘要本身，不解释、不加前缀、不加标题、不使用列表符号。"
            ),
            QwenMessage(
                "user",
                "请将下面这段音频的文字摘要再压缩为不超过 125 个汉字的简短摘要，保留关键要点、人物与结论。只输出摘要本身：\n\n$firstSummary"
            )
        )

        val out = StringBuilder()
        var failed = false
        try {
            qwenCloudService.chat(
                messages = messages,
                apiKey = apiKey,
                enableSearch = false,
                enableThinking = false
            ).catch { _ -> failed = true }
             .collect { delta ->
                when (delta) {
                    is QwenStreamDelta.Content -> out.append(delta.text)
                    is QwenStreamDelta.Error -> failed = true
                    is QwenStreamDelta.Thinking -> { /* ignore */ }
                }
            }
        } catch (_: Exception) {
            failed = true
        }
        if (failed) return false
        val condensed = out.toString().trim()
            .replace("\n", " ")
            .take(LocalContentSyncManager.SECOND_SUMMARY_MAX_CHARS)
        if (condensed.isBlank()) return false

        // Re-verify the "no existing second summary" gate right before writing.
        val fresh = recordingDao.getRecordingById(recordingId) ?: return false
        if (!fresh.secondSummaryText.isNullOrBlank()) return false
        if (fresh.summaryText.isNullOrBlank()) return false  // someone cleared it mid-flight
        recordingDao.update(fresh.copy(secondSummaryText = condensed))
        return true
    }

    // ============================================================

    fun deleteTranscription(recording: RecordingItem) {
        viewModelScope.launch {
            recording.transcriptionPath?.let {
                File(it).delete()
            }
            val updatedRecording = recording.copy(transcriptionPath = null)
            recordingDao.update(updatedRecording)
        }
    }

    fun deleteRecording(recording: RecordingItem) {
        viewModelScope.launch(Dispatchers.IO) {
            recording.transcriptionPath?.let { path ->
                val transFile = File(path)
                if (transFile.exists()) transFile.delete()
            }

            val audioFile = File(recording.filePath)
            if (audioFile.exists()) audioFile.delete()

            recordingDao.delete(recording.id)
        }
    }

    fun renameRecording(recording: RecordingItem, newName: String) {
        viewModelScope.launch {
            val oldFile = File(recording.filePath)
            val extension = oldFile.extension.ifEmpty { "m4a" }
            val newFile = File(oldFile.parent, newName + "." + extension)
            if (oldFile.renameTo(newFile)) {
                var newTranscriptionPath: String? = null
                recording.transcriptionPath?.let {
                    val oldTranscriptionFile = File(it)
                    val newTranscriptionFile = File(oldTranscriptionFile.parent, newFile.name + ".json")
                    if (oldTranscriptionFile.renameTo(newTranscriptionFile)) {
                        newTranscriptionPath = newTranscriptionFile.absolutePath
                    }
                }
                val updatedRecording = recording.copy(
                    fileName = newFile.name,
                    filePath = newFile.absolutePath,
                    transcriptionPath = newTranscriptionPath
                )
                recordingDao.update(updatedRecording)
            }
        }
    }

    private suspend fun saveTranscription(recording: RecordingItem, result: VoskTranscriptionResult): File = withContext(Dispatchers.IO) {
        val recordingsDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
        val transcriptionFile = File(recordingsDir, "${recording.fileName}.json")
        val jsonString = Json.encodeToString(result)
        transcriptionFile.writeText(jsonString)
        transcriptionFile
    }

    /**
     * Import an audio file from the given URI into the app's recording directory.
     */
    fun importAudioFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val contentResolver = app.contentResolver

                val mimeType = contentResolver.getType(uri)
                val extension = when {
                    mimeType != null -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    else -> null
                } ?: uri.lastPathSegment?.substringAfterLast('.', "m4a") ?: "m4a"

                val supportedExtensions = listOf("mp3", "m4a", "wav", "flac", "aac", "ogg")
                val finalExtension = if (extension.lowercase() in supportedExtensions) extension.lowercase() else "m4a"

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "import_${timeStamp}.${finalExtension}"
                val recordingsDir = app.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
                if (recordingsDir != null && !recordingsDir.exists()) {
                    recordingsDir.mkdirs()
                }
                val destFile = File(recordingsDir, fileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@launch

                val durationStr = try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(destFile.absolutePath)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                    formatDurationMillis(durationMs)
                } catch (e: Exception) {
                    e.printStackTrace()
                    "00:00"
                }

                val newItem = RecordingItem(
                    fileName = fileName,
                    filePath = destFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    duration = durationStr
                )
                recordingDao.insert(newItem)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Share a recording file using Android's share intent via FileProvider.
     */
    fun getShareIntent(recording: RecordingItem): Intent? {
        val app = getApplication<Application>()
        val file = File(recording.filePath)
        if (!file.exists()) return null

        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            file
        )

        val mimeType = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            else -> "audio/*"
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, recording.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatDurationMillis(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voskService.release()
    }

    companion object {
        private const val TRANSCRIPTION_CHANNEL_ID = "transcription_channel"
        private const val TRANSCRIPTION_NOTIFICATION_ID = 1
        private const val TRANSCRIPTION_COMPLETE_NOTIFICATION_ID = 2
    }
}
