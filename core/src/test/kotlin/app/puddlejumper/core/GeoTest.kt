package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val sf = LatLon(37.7749, -122.4194)
    private val la = LatLon(34.0522, -118.2437)

    @Test fun distanceSfToLaIsAbout559Km() {
        assertEquals(559_000.0, Geo.distanceMeters(sf, la), 3_000.0)
    }

    @Test fun circlePointsAreAtRadius() {
        val ring = Geo.circle(sf, 400_000.0, segments = 36)
        assertEquals(37, ring.size)
        assertEquals(ring.first().lat, ring.last().lat, 1e-9)
        ring.forEach { assertEquals(400_000.0, Geo.distanceMeters(sf, it), 1.0) }
    }

    @Test fun circleCrossingAntimeridianStaysInRange() {
        Geo.circle(LatLon(10.0, 179.5), 200_000.0).forEach { assertTrue(it.lon in -180.0..180.0) }
    }

    @Test fun thinKeepsEndpoints() {
        val pts = (0..100).map { LatLon(37.0 + it * 0.001, -122.0) }
        val thinned = Geo.thin(pts, 1_000.0)
        assertEquals(pts.first(), thinned.first())
        assertEquals(pts.last(), thinned.last())
        assertTrue(thinned.size < pts.size)
    }
}
