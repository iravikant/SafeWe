package com.safewe.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "geofence_events")
data class GeofenceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val geofenceId: String,
    val name: String,
    val zone: String,
    val transition: String,
    val timestamp: Long
)

@Dao
interface GeofenceEventDao {
    @Insert suspend fun insert(e: GeofenceEventEntity): Long

    @Query("DELETE FROM geofence_events WHERE id NOT IN (SELECT id FROM geofence_events ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Transaction
    suspend fun insertAndTrim(e: GeofenceEventEntity, keep: Int = 200) { insert(e); trimTo(keep) }

    @Query("SELECT * FROM geofence_events ORDER BY timestamp DESC, id DESC LIMIT 50")
    fun observeRecent(): Flow<List<GeofenceEventEntity>>
}
