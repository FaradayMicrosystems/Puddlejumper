package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangePlannerTest {
    private val km = 1000.0

    @Test fun chargeAtEachStopResetsRange() {
        val s = RangeSettings(fullRangeMeters = 400 * km, reservePercent = 10)
        val legs = RangePlanner.plan(listOf(300 * km, 380 * km), s)
        assertEquals(100 * km, legs[0].arriveRangeMeters, 1e-6)
        assertEquals(400 * km, legs[1].departRangeMeters, 1e-6)
        assertEquals(20 * km, legs[1].arriveRangeMeters, 1e-6)
        assertTrue(legs[0].reachable)
        assertFalse(legs[1].reachable) // 20 km left < 40 km reserve
    }

    @Test fun passThroughCarriesRangeOver() {
        val s = RangeSettings(400 * km, reservePercent = 0, chargeToFullAtEachStop = false)
        val legs = RangePlanner.plan(listOf(150 * km, 150 * km, 150 * km), s)
        assertEquals(listOf(250 * km, 100 * km, -50 * km), legs.map { it.arriveRangeMeters })
        assertEquals(listOf(true, true, false), legs.map { it.reachable })
    }

    @Test fun rejectsBadSettings() {
        assertFailsWith<IllegalArgumentException> { RangeSettings(0.0) }
        assertFailsWith<IllegalArgumentException> { RangeSettings(1.0, reservePercent = 95) }
    }

    @Test fun unitConversion() {
        assertEquals(1609.344 * 250, DistanceUnit.MILES.toMeters(250.0), 1e-6)
        assertEquals("250 mi", DistanceUnit.MILES.format(1609.344 * 250))
    }
}
