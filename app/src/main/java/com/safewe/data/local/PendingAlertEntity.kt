package com.safewe.data.local

import androidx.room.*

/** Geofence alerts waiting to reach the parent. Survives process death, reboots and offline periods. */
@Entity(tableName = "pending_alerts")
data class PendingAlertEntity(
    @PrimaryKey val id: String,
    val geofenceId: String,
    val name: String,
    val zone: String,
    val transition: String,
    val timestamp: Long,
    val attempts: Int
)

@Dao
interface PendingAlertDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: PendingAlertEntity): Long
    @Query("SELECT * FROM pending_alerts ORDER BY timestamp ASC LIMIT :n") suspend fun pending(n: Int): List<PendingAlertEntity>
    @Query("DELETE FROM pending_alerts WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE pending_alerts SET attempts = attempts + 1 WHERE id = :id") suspend fun bump(id: String)
    @Query("DELETE FROM pending_alerts WHERE timestamp < :before") suspend fun deleteOlderThan(before: Long)
    @Query("SELECT COUNT(*) FROM pending_alerts") suspend fun count(): Int
}
