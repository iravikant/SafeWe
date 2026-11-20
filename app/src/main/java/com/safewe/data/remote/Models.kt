package com.safewe.data.remote

data class Pairing(
    val childUid: String = "",
    val parentUid: String = "",
    val status: String = "ACTIVE",
    val code: String = "",
    val createdAt: Long = 0,
    val childAgreementKey: String = "",
    val childSigningKey: String = "",
    val parentAgreementKey: String = "",
    val parentSigningKey: String = ""
)

data class LocationResult(
    val commandId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0,
    val fresh: Boolean = false
)

data class Command(
    val id: String = "",
    val type: String = "",
    val requestedBy: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val status: String = "REQUESTED",
    val param: Int = 0
)

data class HistoryPoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0
)

/** Temporary document; parent deletes it right after reading. 10 small points fit far below 1 MiB. */
data class HistoryResult(
    val commandId: String = "",
    val points: List<HistoryPoint> = emptyList(),
    val createdAt: Long = 0
)

data class GeoPointDto(val lat: Double = 0.0, val lon: Double = 0.0)

/** shape: CIRCLE | POLYGON.  zone: SAFE | RESTRICTED. */
data class GeofenceConfig(
    val id: String = "",
    val name: String = "",
    val shape: String = "CIRCLE",
    val zone: String = "SAFE",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radius: Double = 150.0,
    val points: List<GeoPointDto> = emptyList(),
    val createdAt: Long = 0
)

data class GeofenceAlert(
    val id: String = "",
    val geofenceId: String = "",
    val name: String = "",
    val zone: String = "SAFE",
    val transition: String = "ENTER",
    val timestamp: Long = 0
)

data class UsageApp(val pkg: String = "", val label: String = "", val ms: Long = 0)
data class UsageDay(val day: String = "", val totalMs: Long = 0, val apps: List<UsageApp> = emptyList())

/** Compact summary only: top apps per day. error = "" | "NO_PERMISSION". */
data class UsageResult(
    val commandId: String = "",
    val days: List<UsageDay> = emptyList(),
    val error: String = "",
    val generatedAt: Long = 0
)
