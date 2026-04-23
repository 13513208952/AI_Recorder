package com.example.airecorder03.database

import androidx.compose.runtime.saveable.Saver
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val timestamp: Long,
    val duration: String,
    val transcriptionPath: String? = null,
    val summaryText: String? = null,
    val secondSummaryText: String? = null
) {
    companion object {
        val saver: Saver<RecordingItem?, Any> = Saver(
            save = { recordingItem ->
                recordingItem?.let {
                    listOf(it.id, it.fileName, it.filePath, it.timestamp, it.duration, it.transcriptionPath, it.summaryText, it.secondSummaryText)
                }
            },
            restore = { savedValue ->
                (savedValue as? List<*>)?.let { list ->
                    if (list.size < 6) return@let null
                    RecordingItem(
                        id = (list[0] as Number).toLong(),
                        fileName = list[1] as String,
                        filePath = list[2] as String,
                        timestamp = (list[3] as Number).toLong(),
                        duration = list[4] as String,
                        transcriptionPath = list[5] as? String,
                        summaryText = if (list.size > 6) list[6] as? String else null,
                        secondSummaryText = if (list.size > 7) list[7] as? String else null
                    )
                }
            }
        )
    }
}
