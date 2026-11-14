package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeTreeTest {
    private val car = EvModel(usableKwh = 75.0, kwhPer100Km = 18.0, massKg = 2_000.0)

    /** A straight road due north from (45, 7), [km] long, one point per km. */
    private fun northRoad(km: Int) = Path(List(km + 1) { Geo.destination(LatLon(45.0, 7.0), 0.0, it * 1000.0) })

    @Test fun fullRangeFromCapacityAndConsumption() {
        assertEquals(416_666.7, car.fullRangeMeters, 1.0)
        assertEquals(18.0 * 0.5, car.flatKwh(50_000.0), 1e-9)
    }

    @Test fun budgetStopsAtReserve() {
        assertEquals(75.0 * 0.7, car.budgetKwh(80.0, 10), 1e-9)
        assertEquals(0.0, car.budgetKwh(5.0, 10), 1e-9)
    }

    @Test fun climbCostsMoreThanDescentGivesBack() {
        val up = car.climbKwh(1_000.0)
        val down = car.climbKwh(-1_000.0)
        assertEquals(2_000 * 9.81 * 1_000 / 3.6e6 / 0.9, up, 1e-9) // about 6.1 kWh
        assertTrue(down < 0 && -down < up)
    }

    @Test fun flatRoadIsCutAtFlatRange() {
        val budget = car.flatKwh(100_000.0) // exactly 100 km of flat road
        val b = RangeTree.clip(0.0, northRoad(300), emptyList(), car, budget)
        assertTrue(b.batteryLimited)
        assertEquals(100_000.0, b.roadMeters, 50.0)
        assertEquals(100_000.0, Geo.distanceMeters(b.points.first(), b.points.last()), 100.0)
    }

    @Test fun climbingShortensTheBranch() {
        val road = northRoad(300)
        val budget = car.flatKwh(100_000.0)
        // Road climbs 1,000 m over its first 100 km, then stays level.
        val hills = listOf(0.0 to 0.0, 100_000.0 to 1_000.0)
        val b = RangeTree.clip(0.0, road, hills, car, budget)
        // Climbing adds about 0.061 kWh/km to 0.18 kWh/km: 18 kWh / 0.241 kWh/km is about 74.8 km.
        assertEquals(74_800.0, b.roadMeters, 500.0)
    }

    @Test fun descendingLengthensTheBranch() {
        val road = northRoad(300)
        val budget = car.flatKwh(100_000.0)
        val downhill = listOf(0.0 to 1_000.0, 100_000.0 to 0.0)
        val b = RangeTree.clip(0.0, road, downhill, car, budget)
        assertTrue(b.roadMeters > 115_000.0, "got ${b.roadMeters}")
    }

    @Test fun roadEndingFirstIsNotBatteryLimited() {
        val b = RangeTree.clip(0.0, northRoad(20), emptyList(), car, car.flatKwh(100_000.0))
        assertFalse(b.batteryLimited)
        assertEquals(20_000.0, b.roadMeters, 50.0)
    }

    @Test fun emptyBudgetGivesJustTheOrigin() {
        val b = RangeTree.clip(0.0, northRoad(20), emptyList(), car, 0.0)
        assertEquals(1, b.points.size)
    }

    @Test fun targetsFanOutEvery15Degrees() {
        val o = LatLon(45.0, 7.0)
        val t = RangeTree.targets(o, 300_000.0)
        assertEquals(24, t.size)
        t.forEachIndexed { i, p ->
            assertEquals(300_000.0, Geo.distanceMeters(o, p), 1.0)
            val diff = (Geo.initialBearing(o, p) - i * 15.0 + 540) % 360 - 180
            assertEquals(0.0, diff, 0.01)
        }
    }

    @Test fun elevationSamplesCoverPathUpToLimit() {
        val s = RangeTree.elevationSamples(northRoad(100), 3_000.0, 50_000.0)
        assertEquals(0.0, s.first().first)
        assertEquals(50_000.0, s.last().first, 1e-6)
        assertEquals(18, s.size) // 0, 3, ... 48 km, plus 50 km
    }

    @Test fun decodesPolyline6() {
        // Mapbox/OSRM docs example (precision 5) re-encoded: points (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
        val p5 = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p5.size)
        assertEquals(38.5, p5[0].lat, 1e-9)
        assertEquals(-120.2, p5[0].lon, 1e-9)
        assertEquals(43.252, p5[2].lat, 1e-9)
        assertEquals(-126.453, p5[2].lon, 1e-9)
    }

    @Test fun parsesBranchRoute() {
        val json = """{"code":"Ok","routes":[{"geometry":"_p~iF~ps|U_ulLnnqC","distance":1}]}"""
        val path = BranchRouter.osrm().parse(json)!!
        assertEquals(2, path.points.size)
        assertEquals(3.85, path.points[0].lat, 1e-9) // precision 6 reading of a precision-5 string
        assertEquals(null, BranchRouter.osrm().parse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test fun branchUrls() {
        val a = LatLon(45.0, 7.0)
        val b = LatLon(46.0, 8.0)
        assertEquals(
            "https://router.project-osrm.org/route/v1/driving/7.00000,45.00000;8.00000,46.00000?overview=full&geometries=polyline6&steps=false",
            BranchRouter.osrm().url(a, b),
        )
        assertTrue(BranchRouter.mapbox("pk.x").url(a, b).contains("/mapbox/driving/7.00000,45.00000;8.00000,46.00000?"))
    }

    @Test fun elevationApi() {
        assertEquals(
            "https://api.open-meteo.com/v1/elevation?latitude=45.0000,46.5000&longitude=7.0000,8.2500",
            OpenMeteoElevation.url(listOf(LatLon(45.0, 7.0), LatLon(46.5, 8.25))),
        )
        assertEquals(listOf(512.0, null), OpenMeteoElevation.parse("""{"elevation":[512.0,null]}"""))
        assertEquals("45.12,7.99", OpenMeteoElevation.key(LatLon(45.1234, 7.9876)))
    }

    @Test fun plannerStartsFromCurrentBattery() {
        val s = RangeSettings(fullRangeMeters = 400_000.0, reservePercent = 0, startRangeMeters = 200_000.0)
        val legs = RangePlanner.plan(listOf(150_000.0, 150_000.0), s)
        assertEquals(50_000.0, legs[0].arriveRangeMeters, 1e-6)
        assertEquals(400_000.0, legs[1].departRangeMeters, 1e-6)
    }
}
