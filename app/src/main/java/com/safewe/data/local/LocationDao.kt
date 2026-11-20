package com.safewe.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(entity: LocationEntity): Long

    /** Keeps only the newest [keep] rows. */
    @Query("DELETE FROM locations WHERE id NOT IN (SELECT id FROM locations ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    /** Insert + trim in ONE transaction: the table never holds more than [keep] rows. */
    @Transaction
    suspend fun insertAndTrim(entity: LocationEntity, keep: Int = MAX_RECORDS) {
        insert(entity)
        trimTo(keep)
    }

    @Query("SELECT * FROM locations ORDER BY timestamp DESC, id DESC LIMIT 10")
    suspend fun latest10(): List<LocationEntity>

    @Query("SELECT * FROM locations ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latest(): LocationEntity?

    @Query("SELECT * FROM locations ORDER BY timestamp DESC, id DESC LIMIT 10")
    fun observeLatest10(): Flow<List<LocationEntity>>

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun count(): Int

    companion object { const val MAX_RECORDS = 10 }
}
