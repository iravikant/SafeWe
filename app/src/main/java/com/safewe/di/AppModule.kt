package com.safewe.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.safewe.data.local.AppDatabase
import com.safewe.data.local.LocationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun auth(): FirebaseAuth = FirebaseAuth.getInstance()
    /** Small persistent cache (20 MB): offline writes are queued by Firestore, but the local copy never grows unbounded. */
    @Provides @Singleton fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(com.google.firebase.firestore.PersistentCacheSettings.newBuilder().setSizeBytes(20L * 1024 * 1024).build())
            .build()
    }
    @Provides @Singleton fun db(@ApplicationContext c: Context): AppDatabase =
        Room.databaseBuilder(c, AppDatabase::class.java, "safewe.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
    @Provides fun locationDao(db: AppDatabase): LocationDao = db.locationDao()
    @Provides fun pendingAlertDao(db: AppDatabase) = db.pendingAlertDao()
    @Provides fun processedRequestDao(db: AppDatabase) = db.processedRequestDao()
    @Provides fun usageDao(db: AppDatabase) = db.usageDao()
    @Provides fun geofenceEventDao(db: AppDatabase) = db.geofenceEventDao()
    @Provides @Singleton fun geofencing(@ApplicationContext c: Context): com.google.android.gms.location.GeofencingClient =
        LocationServices.getGeofencingClient(c)
    @Provides @Singleton fun fused(@ApplicationContext c: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(c)
}
