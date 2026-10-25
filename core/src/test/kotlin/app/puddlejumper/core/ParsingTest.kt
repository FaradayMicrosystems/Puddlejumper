package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {
    private fun res(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test fun parsesNrel() {
        val src = NrelSource("DEMO_KEY")
        val stations = src.parse(res("nrel.json"))
        assertEquals(2, stations.size)
        val fast = stations[0]
        assertEquals("nrel:12345", fast.id)
        assertEquals(ChargerSpeed.DC_FAST, fast.maxSpeed)
        assertEquals(ChargerStatus.OPERATIONAL, fast.status)
        assertEquals(6, fast.dcFastPorts)
        assertEquals("1 Main St, Fresno, CA 93721", fast.address)
        assertEquals(listOf("J1772", "CCS1", "CHAdeMO"), fast.connectors.map { it.type })
        assertEquals("2026-09-30", fast.lastUpdated)
        val closed = stations[1]
        assertEquals(ChargerStatus.OUT_OF_SERVICE, closed.status)
        assertEquals(ChargerSpeed.LEVEL2, closed.maxSpeed)
        assertEquals("", closed.address)
    }

    @Test fun nrelUrls() {
        val src = NrelSource("abc")
        val url = src.nearbyUrl(LatLon(36.7, -119.8), 16_093.44)
        assertTrue(url.contains("radius=10.0"), url)
        assertTrue(url.contains("fuel_type=ELEC"))
        val form = src.routeForm(listOf(LatLon(36.0, -119.0), LatLon(37.0, -120.0)), 1609.344)
        assertEquals("LINESTRING(-119.0 36.0,-120.0 37.0)", form["route"])
        assertEquals("1.0", form["distance"])
    }

    @Test fun parsesOpenChargeMap() {
        val stations = OpenChargeMapSource("k").parse(res("ocm.json"))
        val mall = stations[0]
        assertEquals(ChargerSpeed.DC_FAST, mall.maxSpeed)
        assertEquals(150.0, mall.maxPowerKw)
        assertEquals(4, mall.dcFastPorts)
        assertEquals(2, mall.level2Ports)
        assertEquals("ChargePoint", mall.network)
        assertEquals(ChargerStatus.OPERATIONAL, mall.status)
        assertEquals(ChargerStatus.OUT_OF_SERVICE, stations[1].status)
        assertNull(stations[1].maxSpeed)
    }

    @Test fun parsesOsrm() {
        val router = OsrmRouter()
        val route = router.parse(res("osrm.json"))
        assertEquals(3, route.geometry.size)
        assertEquals(LatLon(37.0, -122.0), route.geometry.first())
        assertEquals(listOf(1000.0, 2000.0), route.legs.map { it.distanceMeters })
        assertEquals(3000.0, route.distanceMeters)
        assertEquals(
            listOf("Head out on Main St", "Turn left onto Oak Ave", "Arrive at stop"),
            route.legs[0].steps.map { it.instruction },
        )
        assertTrue(router.routeUrl(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0))).contains("/driving/2.0,1.0;4.0,3.0?"))
    }

    @Test fun osrmErrorThrows() {
        assertFailsWith<RoutingException> { OsrmRouter().parse("""{"code":"NoRoute","message":"Impossible route"}""") }
    }

    @Test fun parsesNominatim() {
        val places = NominatimGeocoder.parse("""[{"display_name":"Reno, NV","lat":"39.5","lon":"-119.8","x":1}]""")
        assertEquals(listOf(Place("Reno, NV", LatLon(39.5, -119.8))), places)
    }
}
