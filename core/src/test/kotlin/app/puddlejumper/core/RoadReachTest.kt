package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadReachTest {
    private val km = 1000.0
    private val center = LatLon(52.52, 13.40)
    private val sampler = RoadReachSampler(bearingCount = 4, ringShares = listOf(0.5, 1.0))

    /**
     * Fake OSRM table answer. Each sample snaps onto itself (or to [snapTo]) and its road distance is
     * the straight distance times [detour]; null detour means unreachable.
     */
    private fun table(range: Double, detour: (ring: Int, b: Int) -> Double?, snapTo: (ring: Int, b: Int) -> Pair<LatLon, Double>? = { _, _ -> null }): String {
        val samples = sampler.samples(center, range)
        val dists = mutableListOf("0")
        val dests = mutableListOf("""{"location":[${center.lon},${center.lat}],"distance":0}""")
        samples.forEachIndexed { j, p ->
            val ring = j / 4
            val b = j % 4
            val (loc, snap) = snapTo(ring, b) ?: (p to 0.0)
            val d = detour(ring, b)
            dists += d?.let { (Geo.distanceMeters(center, loc) * it).toString() } ?: "null"
            dests += """{"location":[${loc.lon},${loc.lat}],"distance":$snap}"""
        }
        return """{"code":"Ok","distances":[[${dists.joinToString(",")}]],"destinations":[${dests.joinToString(",")}]}"""
    }

    @Test fun urlHasSourceAndAllSamples() {
        val url = sampler.tableUrl(center, 400 * km)
        assertTrue(url.startsWith("https://router.project-osrm.org/table/v1/driving/13.40000,52.52000;"))
        assertTrue(url.endsWith("?sources=0&annotations=distance"))
        assertEquals(1 + 8, url.substringAfter("driving/").substringBefore("?").split(";").size)
    }

    @Test fun detourShrinksReach() {
        val p = sampler.parse(table(400 * km, { _, _ -> 1.25 }), center, 400 * km)
        p.radii(400 * km).forEach { assertEquals(320 * km, it, 1.0) } // 400 / 1.25
        // Same profile rescales for a different range without refetching.
        p.radii(200 * km).forEach { assertEquals(160 * km, it, 1.0) }
        assertTrue(p.fits(450 * km))
        assertFalse(p.fits(600 * km))
    }

    @Test fun neverLargerThanStraightLine() {
        // Ratio is clamped to 1: bad data cannot make the area bigger than the old circle.
        val p = sampler.parse(table(300 * km, { _, _ -> 0.5 }), center, 300 * km)
        p.radii(300 * km).forEach { assertEquals(300 * km, it, 1.0) }
    }

    @Test fun outerRingWinsAndInnerFillsIn() {
        // East (b=1): outer unreachable, inner uses detour 2. Others: outer detour 1.25.
        val p = sampler.parse(
            table(400 * km, { ring, b -> if (b == 1) (if (ring == 0) 2.0 else null) else 1.25 }),
            center, 400 * km,
        )
        val r = p.radii(400 * km)
        assertEquals(200 * km, r[1], 1.0)
        assertEquals(320 * km, r[0], 1.0)
    }

    @Test fun unreachableBearingTakesNeighbourAverage() {
        val p = sampler.parse(
            table(400 * km, { _, b -> when (b) { 0 -> 1.0; 2 -> 2.0; 1 -> null; else -> 1.25 } }),
            center, 400 * km,
        )
        // b=1 sits between b=0 (ratio 1.0) and b=2 (ratio 0.5).
        assertEquals(0.75, p.ratio[1], 1e-9)
    }

    @Test fun offRoadOuterSampleCapsReach() {
        // North outer sample is in the sea: OSRM snaps it 150 km back towards the stop (coast at ~250 km).
        val coast = Geo.destination(center, 0.0, 250 * km)
        val p = sampler.parse(
            table(400 * km, { _, _ -> 1.1 }, { ring, b -> if (ring == 1 && b == 0) coast to 150 * km else null }),
            center, 400 * km,
        )
        assertEquals(250 * km, p.capMeters[0]!!, 1.0)
        assertNull(p.capMeters[1])
        assertEquals(250 * km, p.radii(400 * km)[0], 1.0)
        // Multi-jump rings far beyond the sampled range ignore caps (land may lie past the water).
        assertTrue(p.radii(1200 * km)[0] > 250 * km)
    }

    @Test fun polygonIsClosed() {
        val p = sampler.parse(table(400 * km, { _, _ -> 1.25 }), center, 400 * km)
        val ring = p.polygon(400 * km)
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        assertEquals(320 * km, Geo.distanceMeters(center, ring[0]), 50.0)
    }

    @Test fun noRoadsAtAllFails() {
        assertFailsWith<RoutingException> { sampler.parse(table(400 * km, { _, _ -> null }), center, 400 * km) }
        assertFailsWith<RoutingException> {
            sampler.parse("""{"code":"TooBig","message":"Too many table coordinates"}""", center, 400 * km)
        }
    }

    @Test fun bearingMath() {
        assertEquals(90.0, Geo.initialBearing(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-6)
        assertEquals(180.0, Geo.initialBearing(LatLon(1.0, 0.0), LatLon(0.0, 0.0)), 1e-6)
    }
}
