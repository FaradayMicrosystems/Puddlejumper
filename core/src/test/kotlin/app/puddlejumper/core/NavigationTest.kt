package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTest {
    private fun res(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    private val mapbox = MapboxRouter("pk.test")
    private val route = mapbox.parse(res("mapbox.json"))

    /** Points every [stepM] metres along [line]. */
    private fun drive(line: List<LatLon>, stepM: Double): List<LatLon> {
        val out = mutableListOf(line.first())
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val n = (Geo.distanceMeters(a, b) / stepM).toInt().coerceAtLeast(1)
            for (k in 1..n) out += LatLon(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
        }
        return out
    }

    @Test fun parsesMapbox() {
        assertEquals(1, route.legs.size)
        val steps = route.legs[0].steps
        assertEquals(3, steps.size)
        assertEquals("Drive east on Main Street.", steps[0].instruction)
        assertEquals(Maneuver("turn", "left"), steps[1].maneuver)
        assertEquals(3, steps[0].voice.size)
        assertEquals(400.0, steps[0].voice[1].distanceBeforeEndM)
        assertEquals("Oak Lane", steps[0].banners[0].text)
        assertEquals(Maneuver("turn", "left"), steps[0].banners[0].maneuver)
        assertEquals(3, steps[0].geometry.size)
        assertEquals(120.0, route.durationSeconds)
    }

    @Test fun mapboxUrl() {
        val two = mapbox.routeUrl(listOf(LatLon(52.0, 5.0), LatLon(52.1, 5.1)))
        assertTrue(two.startsWith("https://api.mapbox.com/directions/v5/mapbox/driving-traffic/5.000000,52.000000;5.100000,52.100000?"))
        assertTrue("voice_units=metric" in two)
        assertTrue("voice_instructions=true" in two && "banner_instructions=true" in two)
        assertTrue(two.endsWith("access_token=pk.test"))
        assertFalse("bearings" in two)
        val four = mapbox.routeUrl(List(4) { LatLon(52.0 + it, 5.0) }, headingDeg = -90.0)
        assertTrue("/mapbox/driving/" in four, "more than 3 stops can't use live traffic")
        assertTrue("bearings=270,45;;;" in four)
        assertFailsWith<IllegalArgumentException> { mapbox.routeUrl(List(26) { LatLon(52.0, 5.0 + it) }) }
    }

    @Test fun mapboxErrorThrows() {
        val e = assertFailsWith<RoutingException> { mapbox.parse("""{"code":"NoRoute","message":"No route found","routes":[]}""") }
        assertEquals("No route found", e.message)
        assertFailsWith<RoutingException> { mapbox.parse("""{"message":"Not Authorized - Invalid Token"}""") }
    }

    @Test fun drivesTheRouteAndSpeaksEachPromptOnce() {
        val engine = NavigationEngine(route)
        val spoken = mutableListOf<String>()
        var t = 0L
        var last: NavProgress? = null
        var reached = mutableListOf<Int>()
        for (p in drive(route.geometry, 10.0)) {
            val pr = engine.update(NavFix(p, t))
            t += 1_000
            pr.speak?.let { spoken += it }
            pr.reachedStopOfLeg?.let { reached += it }
            assertFalse(pr.offRoute)
            last = pr
        }
        assertEquals(
            listOf(
                "Drive east on Main Street. Then, in 700 metres, turn left onto Oak Lane.",
                "In 400 metres, turn left onto Oak Lane.",
                "Turn left onto Oak Lane.",
                "Continue for 600 metres.",
                "You have arrived at your destination.",
            ),
            spoken,
        )
        assertEquals(listOf(0), reached)
        assertTrue(last!!.arrived)
        assertTrue(last.distanceRemainingM < 1.0)
    }

    @Test fun progressMidRoute() {
        val engine = NavigationEngine(route)
        // 200 m east of the start, 5 m north of the road.
        val p = Geo.destination(Geo.destination(LatLon(52.0, 5.0), 90.0, 200.0), 0.0, 5.0)
        val pr = engine.update(NavFix(p, 0))
        assertEquals(0, pr.stepIndex)
        assertEquals(5.0, pr.distanceFromRouteM, 0.5)
        assertEquals(engine.lengthM - 200.0, pr.distanceRemainingM, 2.0)
        assertEquals(Geo.distanceMeters(LatLon(52.0, 5.0), LatLon(52.0, 5.01)) - 200.0, pr.distanceToManeuverM, 2.0)
        assertEquals("Oak Lane", pr.banner?.text)
        // Joining mid-step: only the latest passed prompt (the opening one) is said.
        assertEquals("Drive east on Main Street. Then, in 700 metres, turn left onto Oak Lane.", pr.speak)
        // Half of step 1's 60 s left, plus step 2's 60 s.
        assertEquals(60.0 * (pr.distanceToManeuverM / Geo.distanceMeters(LatLon(52.0, 5.0), LatLon(52.0, 5.01))) + 60.0, pr.durationRemainingS, 1.0)
    }

    @Test fun offRouteNeedsSeveralFixes() {
        val engine = NavigationEngine(route)
        engine.update(NavFix(LatLon(52.0, 5.002), 0))
        // 120 m south of the road: off route only after 3 readings in a row.
        val off = Geo.destination(LatLon(52.0, 5.004), 180.0, 120.0)
        assertFalse(engine.update(NavFix(off, 1000)).offRoute)
        assertFalse(engine.update(NavFix(off, 2000)).offRoute)
        val third = engine.update(NavFix(off, 3000))
        assertTrue(third.offRoute)
        assertNull(third.speak)
        // Back on the road: fine again.
        assertFalse(engine.update(NavFix(LatLon(52.0, 5.005), 4000)).offRoute)
    }

    @Test fun farOffRouteIsImmediate() {
        val engine = NavigationEngine(route)
        engine.update(NavFix(LatLon(52.0, 5.002), 0))
        assertTrue(engine.update(NavFix(Geo.destination(LatLon(52.0, 5.004), 180.0, 400.0), 1000)).offRoute)
    }

    @Test fun poorGpsWidensTolerance() {
        val engine = NavigationEngine(route)
        engine.update(NavFix(LatLon(52.0, 5.002), 0))
        val off = Geo.destination(LatLon(52.0, 5.004), 180.0, 90.0)
        repeat(5) { assertFalse(engine.update(NavFix(off, 1000L * it + 1, accuracyM = 60.0)).offRoute) }
    }

    @Test fun osrmRouteGetsPromptsAndMultipleLegs() {
        val router = OsrmRouter()
        val r = router.parse(res("osrm.json"))
        val steps = r.legs[0].steps
        assertEquals("Head out on Main St.", steps[0].voice.first().text)
        assertEquals("Turn left onto Oak Ave.", steps[0].voice.last().text)
        assertEquals("In 500 metres, turn left onto Oak Ave.", steps[0].voice[1].text)
        assertEquals("Turn left onto Oak Ave", steps[0].banners.single().text)
        assertTrue(steps.last().voice.isEmpty())
        // OSRM fixture has no step lines: the engine spreads steps over the route line.
        val engine = NavigationEngine(r)
        val reached = mutableListOf<Int>()
        for ((i, p) in drive(r.geometry, 20.0).withIndex()) engine.update(NavFix(p, i * 1000L)).reachedStopOfLeg?.let { reached += it }
        assertEquals(listOf(0, 1), reached)
    }

    @Test fun spokenDistances() {
        assertEquals("450 metres", Guidance.spokenDistance(440.0))
        assertEquals("80 metres", Guidance.spokenDistance(83.0))
        assertEquals("1 kilometre", Guidance.spokenDistance(990.0))
        assertEquals("2 kilometres", Guidance.spokenDistance(2_000.0))
        assertEquals("1.5 kilometres", Guidance.spokenDistance(1_520.0))
        assertEquals("2.4 km", Guidance.shortDistance(2_380.0))
        assertEquals("35 km", Guidance.shortDistance(35_200.0))
        assertEquals("450 m", Guidance.shortDistance(440.0))
    }

    @Test fun arrowsAndDurations() {
        assertEquals("\u2190", Guidance.arrow(Maneuver("turn", "left")))
        assertEquals("\u2197", Guidance.arrow(Maneuver("fork", "slight right")))
        assertEquals("\u21BB", Guidance.arrow(Maneuver("roundabout", "right")))
        assertEquals("\u2691", Guidance.arrow(Maneuver("arrive", "straight")))
        assertEquals("\u2191", Guidance.arrow(Maneuver("depart")))
        assertEquals("14 min", Guidance.duration(840.0))
        assertEquals("1 h 05 min", Guidance.duration(3_900.0))
    }

    @Test fun rerouteGate() {
        val g = RerouteGate(10_000)
        assertTrue(g.tryStart(0))
        assertFalse(g.tryStart(20_000), "still busy")
        g.done()
        assertFalse(g.tryStart(5_000))
        assertTrue(g.tryStart(10_000))
    }
}
