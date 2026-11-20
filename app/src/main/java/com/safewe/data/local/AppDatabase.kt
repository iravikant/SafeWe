package com.safewe.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationEntity::class, GeofenceEventEntity::class, UsageDayEntity::class, ProcessedRequestEntity::class, PendingAlertEntity::class],
    version = 5, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun geofenceEventDao(): GeofenceEventDao
    abstract fun usageDao(): UsageDao
    abstract fun processedRequestDao(): ProcessedRequestDao
    abstract fun pendingAlertDao(): PendingAlertDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `geofence_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `geofenceId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `zone` TEXT NOT NULL, `transition` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_days` (`day` TEXT NOT NULL, `pkg` TEXT NOT NULL, " +
                        "`foregroundMs` INTEGER NOT NULL, PRIMARY KEY(`day`, `pkg`))"
                )
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `processed_requests` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_alerts` (`id` TEXT NOT NULL, `geofenceId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`zone` TEXT NOT NULL, `transition` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
