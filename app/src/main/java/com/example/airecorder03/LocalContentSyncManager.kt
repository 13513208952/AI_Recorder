package com.example.airecorder03

import android.content.Context
import com.example.airecorder03.database.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class LocalContentSyncEntry(
    val fileName: String,
    val duration: String,
    val timestamp: Long,
    val recordedAt: String,
    val secondSummary: String? = null
)

@Serializable
data class LocalContentSyncIndex(
    val updatedAt: Long,
    val files: List<LocalContentSyncEntry>
)

/**
 * Manages the local content sync JSON index.
 *
 * The index records, for every recording currently in the library, its file name,
 * duration, timestamp, and (if present) the "second summary" — a short ≤125 char
 * summary of the first AI summary. The second summary never surfaces in the UI; it
 * only lives in this index for injection into cloud chat context.
 */
class LocalContentSyncManager(private val context: Context) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val indexFile: File
        get() = File(context.filesDir, INDEX_FILENAME)

    /**
     * Rebuild the on-disk index from the given live list of recordings.
     * - Removes entries whose recordings no longer exist.
     * - Updates fileName/duration/timestamp for existing entries.
     * - Writes the current second summary from the DB (source of truth).
     */
    suspend fun refresh(recordings: List<RecordingItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = recordings.map { rec ->
                LocalContentSyncEntry(
                    fileName = rec.fileName,
                    duration = rec.duration,
                    timestamp = rec.timestamp,
                    recordedAt = dateFormatter.format(Date(rec.timestamp)),
                    secondSummary = rec.secondSummaryText
                )
            }
            val index = LocalContentSyncIndex(
                updatedAt = System.currentTimeMillis(),
                files = entries
            )
            val file = indexFile
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(index))
        }
    }

    /**
     * Read the current index as a JSON string. Returns a minimal empty index if no
     * file exists yet (should not normally happen — refresh runs at startup).
     */
    suspend fun readJsonString(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = indexFile
            if (file.exists()) {
                file.readText()
            } else {
                json.encodeToString(
                    LocalContentSyncIndex(updatedAt = System.currentTimeMillis(), files = emptyList())
                )
            }
        }
    }

    /** Delete the backing index file. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = indexFile
            if (file.exists()) file.delete()
        }
    }

    companion object {
        private const val INDEX_FILENAME = "local_content_sync.json"

        // 20MB limit matches QwenCloudService.summarizeAudio's inline-upload limit.
        const val MAX_AUDIO_SIZE_BYTES: Long = 20L * 1024L * 1024L

        const val SECOND_SUMMARY_MAX_CHARS = 125
    }
}
