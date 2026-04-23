package com.example.airecorder03.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: RecordingItem)

    @Update
    suspend fun update(recording: RecordingItem)

    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<RecordingItem>>

    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    suspend fun getAllRecordingsOnce(): List<RecordingItem>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingItem?

    @Query("UPDATE recordings SET summaryText = NULL WHERE id = :id")
    suspend fun clearFirstSummary(id: Long)

    @Query("UPDATE recordings SET secondSummaryText = NULL WHERE id = :id")
    suspend fun clearSecondSummary(id: Long)

    @Query("UPDATE recordings SET summaryText = NULL")
    suspend fun clearAllFirstSummaries()

    @Query("UPDATE recordings SET secondSummaryText = NULL")
    suspend fun clearAllSecondSummaries()

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}
