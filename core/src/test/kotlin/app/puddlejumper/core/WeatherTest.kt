package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private fun res(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test fun parsesOpenMeteo() {
        val at = LatLon(52.52, 13.41)
        val f = OpenMeteo.parse(res("openmeteo.json"), at)
        assertEquals(at, f.location)
        assertEquals(listOf("2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02"), f.days.map { it.date })
        val wet = f.days[1]
        assertEquals(WeatherCondition.RAIN, wet.condition)
        assertEquals(9.8, wet.maxC)
        assertEquals(3.0, wet.minC)
        assertEquals(7.6, wet.precipitationMm)
        assertEquals(85, wet.precipitationChancePercent)
        assertEquals(31.7, wet.maxWindKmh)
        assertEquals(WeatherCondition.CLOUDY, f.days[0].condition)
        val missing = f.days[2]
        assertEquals(WeatherCondition.UNKNOWN, missing.condition)
        assertNull(missing.maxC)
    }

    @Test fun forecastUrlIsMetricAndFiveDays() {
        val url = OpenMeteo.forecastUrl(LatLon(52.52, 13.41))
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.5200&longitude=13.4100"), url)
        assertTrue(url.contains("&forecast_days=5"), url)
        // Metric is Open-Meteo's default, so no unit overrides may be sent.
        assertTrue("fahrenheit" !in url && "mph" !in url && "inch" !in url, url)
    }

    @Test fun mapsWmoCodes() {
        assertEquals(WeatherCondition.CLEAR, WeatherCondition.fromWmo(0))
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromWmo(75))
        assertEquals(WeatherCondition.FREEZING_RAIN, WeatherCondition.fromWmo(66))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherCondition.fromWmo(99))
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromWmo(null))
    }

    @Test fun formatsMetricText() {
        val d = OpenMeteo.parse(res("openmeteo.json"), LatLon(0.0, 0.0)).days
        assertEquals("Fri 30 Oct", WeatherFormat.day(d[1]))
        assertEquals("🌧 10°/3°", WeatherFormat.badge(d[1]))
        assertEquals("Fri 30 Oct: Rain, 3 to 10 °C, 7.6 mm (85%), wind up to 32 km/h", WeatherFormat.detail(d[1]))
        assertEquals("? –/–", WeatherFormat.badge(d[2]))
        assertEquals("Sat 31 Oct: Unknown", WeatherFormat.detail(d[2]))
        // Map badge: first 3 of the 5 days.
        assertEquals("☁ 12°/5°  ·  🌧 10°/3°  ·  ? –/–", WeatherFormat.badge(d))
        assertEquals("Mon 2 Nov: Snow, -3 to 2 °C, 4.0 mm (70%), wind up to 20 km/h", WeatherFormat.detail(d[4]))
    }
}
