package com.safewe.data.local

import androidx.room.*

@Entity(tableName = "processed_requests")
data class ProcessedRequestEntity(@PrimaryKey val id: String, val at: Long)

@Dao
interface ProcessedRequestDao {
    /** Returns -1 when the ID already exists (replay / duplicate delivery). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: ProcessedRequestEntity): Long

    @Query("DELETE FROM processed_requests WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)
}
