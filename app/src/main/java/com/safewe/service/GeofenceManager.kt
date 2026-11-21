package com.safewe.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.safewe.data.local.GeofenceEventDao
import com.safewe.data.local.GeofenceEventEntity
import com.safewe.data.remote.AuthRepository
import com.safewe.data.remote.GeofenceAlert
import com.safewe.data.remote.GeofenceConfig
import com.safewe.data.remote.GeofenceRepository
import com.safewe.domain.geo.AlertPolicy
import com.safewe.domain.geo.GeoPoint
import com.safewe.domain.geo.PolygonTracker
import com.safewe.domain.geo.Transition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Child-side geofencing.
 *  - CIRCLE  -> Google Play services GeofencingClient (ENTER / EXIT / DWELL, delivered to [GeofenceReceiver]).
 *  - POLYGON -> evaluated locally on each location update (point-in-polygon + hysteresis + accuracy gate).
 *    Polygon accuracy therefore depends on how often location updates arrive.
 * Android allows at most 100 geofences per app; extra circles are skipped.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val client: GeofencingClient,
    private val repo: GeofenceRepository,
    private val auth: AuthRepository,
    private val events: GeofenceEventDao,
    private val outbox: com.safewe.data.local.PendingAlertDao
) {
    private val configs = ConcurrentHashMap<String, GeofenceConfig>()
    private val trackers = ConcurrentHashMap<String, PolygonTracker>()
    private val primed = ConcurrentHashMap.newKeySet<String>()
    private var job: Job? = null

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // geofencing needs a mutable PI
        )
    }

    fun start(scope: CoroutineScope, childUid: String) {
        job?.cancel()
        job = scope.launch { repo.observe(childUid).collect { apply(it) } }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { client.removeGeofences(pendingIntent) }
        trackers.clear(); primed.clear(); configs.clear()
    }

    @SuppressLint("MissingPermission")
    private fun apply(list: List<GeofenceConfig>) {
        configs.clear(); list.forEach { configs[it.id] = it }

        // polygons
        trackers.keys.retainAll(list.filter { it.shape == "POLYGON" }.map { it.id }.toSet())
        list.filter { it.shape == "POLYGON" && it.points.size >= 3 }.forEach { cfg ->
            trackers.getOrPut(cfg.id) { PolygonTracker(cfg.points.map { GeoPoint(it.lat, it.lon) }) }
        }

        // circles
        runCatching {
            client.removeGeofences(pendingIntent).addOnCompleteListener {
                val circles = list.filter { it.shape == "CIRCLE" && it.radius >= 50 }.take(100).map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.lat, it.lon, it.radius.toFloat())
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                        .setLoiteringDelay(5 * 60 * 1000)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .build()
                }
                if (circles.isNotEmpty()) {
                    val req = GeofencingRequest.Builder()
                        .setInitialTrigger(0) // no synthetic events on registration
                        .addGeofences(circles).build()
                    client.addGeofences(req, pendingIntent).addOnFailureListener { e ->
                        android.util.Log.e("GeofenceManager", "Failed to add geofences", e)
                    }
                }
            }
        }.onFailure { e ->
            android.util.Log.e("GeofenceManager", "Failed to remove geofences before update", e)
        }
    }

    /** Called from the location callback for polygon evaluation. */
    fun onLocation(lat: Double, lon: Double, accuracy: Float) {
        for ((id, tracker) in trackers) {
            val t = tracker.update(GeoPoint(lat, lon), accuracy)
            if (primed.add(id)) continue            // first valid fix only sets the baseline state
            val name = when (t) { Transition.ENTER -> "ENTER"; Transition.EXIT -> "EXIT"; null -> continue }
            CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { handleTransition(id, name) }
        }
    }

    suspend fun handleTransition(geofenceId: String, transition: String) {
        val uid = auth.uid ?: return
        val cfg = configs[geofenceId] ?: runCatching { repo.fetch(uid, geofenceId) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        events.insertAndTrim(GeofenceEventEntity(geofenceId = cfg.id, name = cfg.name, zone = cfg.zone, transition = transition, timestamp = now))
        if (AlertPolicy.shouldAlert(cfg.zone, transition)) {
            // Deterministic ID (per minute) -> duplicate callbacks collapse into one document.
            val alertId = "${cfg.id}_${transition}_${now / 60_000}"
            // Durable queue: survives offline periods, process death and reboots; WorkManager retries with backoff.
            outbox.insert(com.safewe.data.local.PendingAlertEntity(alertId, cfg.id, cfg.name, cfg.zone, transition, now, 0))
            OutboxWorker.enqueue(ctx)
        }
    }
}
