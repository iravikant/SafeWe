package com.safewe

import com.safewe.domain.geo.*
import org.junit.Assert.*
import org.junit.Test

class GeoMathTest {
    // ~1.1 km square around (0,0)
    private val sq = listOf(GeoPoint(0.005, -0.005), GeoPoint(0.005, 0.005), GeoPoint(-0.005, 0.005), GeoPoint(-0.005, -0.005))

    @Test fun insideAndOutside() {
        assertTrue(GeoMath.pointInPolygon(GeoPoint(0.0, 0.0), sq))
        assertFalse(GeoMath.pointInPolygon(GeoPoint(0.02, 0.0), sq))
    }

    @Test fun haversineOneDegreeLatIsAbout111km() {
        val d = GeoMath.haversine(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertEquals(111_195.0, d, 300.0)
    }

    @Test fun boundaryDistanceIsReasonable() {
        val d = GeoMath.distanceToBoundary(GeoPoint(0.0, 0.0), sq)
        assertEquals(556.0, d, 10.0)
    }

    @Test fun trackerEntersThenExitsWithHysteresis() {
        val t = PolygonTracker(sq, hysteresisMeters = 50.0, maxAccuracyMeters = 50f)
        assertEquals(Transition.ENTER, t.update(GeoPoint(0.0, 0.0), 10f))
        // just outside the edge (~11 m): ignored by hysteresis
        assertNull(t.update(GeoPoint(0.0, 0.0051), 10f))
        // far outside: EXIT
        assertEquals(Transition.EXIT, t.update(GeoPoint(0.0, 0.02), 10f))
    }

    @Test fun poorAccuracyIsIgnored() {
        val t = PolygonTracker(sq, maxAccuracyMeters = 50f)
        assertNull(t.update(GeoPoint(0.0, 0.0), 500f))
    }
}
