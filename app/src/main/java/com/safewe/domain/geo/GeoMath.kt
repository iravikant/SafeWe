package com.safewe.domain.geo

import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)

object GeoMath {
    private const val R = 6_371_000.0

    fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Ray-casting point-in-polygon. Polygon need not be explicitly closed. */
    fun pointInPolygon(p: GeoPoint, poly: List<GeoPoint>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            val crosses = (a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance in metres from [p] to the nearest polygon edge (local equirectangular approximation). */
    fun distanceToBoundary(p: GeoPoint, poly: List<GeoPoint>): Double {
        val latScale = Math.toRadians(1.0) * R
        val lonScale = latScale * cos(Math.toRadians(p.lat))
        fun xy(g: GeoPoint) = ((g.lon - p.lon) * lonScale) to ((g.lat - p.lat) * latScale)
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val (x1, y1) = xy(poly[i]); val (x2, y2) = xy(poly[(i + 1) % poly.size])
            val dx = x2 - x1; val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-x1) * dx + (-y1) * dy).div(len2).coerceIn(0.0, 1.0)
            best = min(best, hypot(x1 + t * dx, y1 + t * dy))
        }
        return best
    }
}

enum class Transition { ENTER, EXIT }

/**
 * Polygon geofence tracker with accuracy validation + boundary hysteresis.
 * A transition fires only when the fix is accurate enough AND the point is
 * more than [hysteresisMeters] beyond the boundary, which prevents ENTER/EXIT flapping.
 */
class PolygonTracker(
    private val polygon: List<GeoPoint>,
    private val hysteresisMeters: Double = 20.0,
    private val maxAccuracyMeters: Float = 100f
) {
    private var inside: Boolean? = null

    fun update(p: GeoPoint, accuracy: Float): Transition? {
        if (accuracy > maxAccuracyMeters) return null
        val isIn = GeoMath.pointInPolygon(p, polygon)
        val dist = GeoMath.distanceToBoundary(p, polygon)
        val prev = inside
        if (prev == null) { inside = isIn; return if (isIn) Transition.ENTER else null }
        if (isIn == prev) return null
        if (dist < hysteresisMeters) return null // too close to the edge: ignore
        inside = isIn
        return if (isIn) Transition.ENTER else Transition.EXIT
    }
}
