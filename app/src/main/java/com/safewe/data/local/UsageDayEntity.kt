package com.safewe.data.local

import androidx.room.*

@Entity(tableName = "usage_days", primaryKeys = ["day", "pkg"])
data class UsageDayEntity(val day: String, val pkg: String, val foregroundMs: Long)

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<UsageDayEntity>)

    @Query("SELECT * FROM usage_days WHERE day = :day")
    suspend fun forDay(day: String): List<UsageDayEntity>

    @Query("SELECT * FROM usage_days WHERE day >= :fromDay")
    suspend fun since(fromDay: String): List<UsageDayEntity>

    @Query("DELETE FROM usage_days WHERE day < :day")
    suspend fun deleteOlderThan(day: String)

    @Query("DELETE FROM usage_days")
    suspend fun clear()
}
