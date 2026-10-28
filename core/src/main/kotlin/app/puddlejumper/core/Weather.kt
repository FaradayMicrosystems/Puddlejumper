package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One day of forecast. Metric: °C, mm, km/h. Any value can be missing. */
data class DailyForecast(
    val date: String,
    val condition: WeatherCondition,
    val maxC: Double?,
    val minC: Double?,
    val precipitationMm: Double?,
    val precipitationChancePercent: Int?,
    val maxWindKmh: Double?,
)

data class Forecast(val location: LatLon, val days: List<DailyForecast>)

/** WMO weather codes grouped into what a driver cares about. */
enum class WeatherCondition(val label: String, val symbol: String) {
    CLEAR("Clear", "☀"),
    PARTLY_CLOUDY("Partly cloudy", "⛅"),
    CLOUDY("Cloudy", "☁"),
    FOG("Fog", "🌫"),
    DRIZZLE("Drizzle", "🌦"),
    RAIN("Rain", "🌧"),
    FREEZING_RAIN("Freezing rain", "🧊"),
    SNOW("Snow", "❄"),
    THUNDERSTORM("Thunderstorm", "⛈"),
    UNKNOWN("Unknown", "?");

    companion object {
        fun fromWmo(code: Int?): WeatherCondition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57, 66, 67 -> FREEZING_RAIN
            61, 63, 65, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDERSTORM
            else -> UNKNOWN
        }
    }
}

/**
 * Open-Meteo (open-meteo.com): free weather forecasts, no key needed for non-commercial use.
 * Its default units are already metric (°C, mm, km/h).
 */
object OpenMeteo {
    const val DAYS = 3

    fun forecastUrl(at: LatLon, days: Int = DAYS): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.ROOT, at.lat, at.lon) +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,wind_speed_10m_max" +
            "&forecast_days=$days&timezone=auto"

    fun parse(json: String, at: LatLon): Forecast {
        val d = JSON.decodeFromString<Response>(json).daily
        val days = d.time.mapIndexed { i, date ->
            DailyForecast(
                date = date,
                condition = WeatherCondition.fromWmo(d.weatherCode.getOrNull(i)),
                maxC = d.tempMax.getOrNull(i),
                minC = d.tempMin.getOrNull(i),
                precipitationMm = d.precipitation.getOrNull(i),
                precipitationChancePercent = d.precipitationChance.getOrNull(i),
                maxWindKmh = d.windMax.getOrNull(i),
            )
        }
        return Forecast(at, days)
    }

    @Serializable
    private data class Response(val daily: Daily = Daily())

    @Serializable
    private data class Daily(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
        @SerialName("precipitation_sum") val precipitation: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val precipitationChance: List<Int?> = emptyList(),
        @SerialName("wind_speed_10m_max") val windMax: List<Double?> = emptyList(),
    )

    private val JSON = Json { ignoreUnknownKeys = true }
}

/** Text for weather markers and the forecast dialog. Always metric. */
object WeatherFormat {
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)

    /** "Thu 29 Oct", or the raw date if it doesn't parse. */
    fun day(d: DailyForecast): String =
        runCatching { java.time.LocalDate.parse(d.date).format(DAY) }.getOrDefault(d.date)

    /** Short marker label, e.g. "☁ 12°/5°". */
    fun badge(d: DailyForecast): String =
        "${d.condition.symbol} ${deg(d.maxC)}/${deg(d.minC)}"

    /** e.g. "Thu 29 Oct: Rain, 3 to 10 °C, 7.6 mm (85%), wind up to 32 km/h". */
    fun detail(d: DailyForecast): String = buildString {
        append("${day(d)}: ${d.condition.label}")
        if (d.minC != null && d.maxC != null) append(", %.0f to %.0f °C".format(java.util.Locale.ROOT, d.minC, d.maxC))
        d.precipitationMm?.let { append(", %.1f mm".format(java.util.Locale.ROOT, it)) }
        d.precipitationChancePercent?.let { append(" ($it%)") }
        d.maxWindKmh?.let { append(", wind up to %.0f km/h".format(java.util.Locale.ROOT, it)) }
    }

    private fun deg(c: Double?) = c?.let { "%.0f°".format(java.util.Locale.ROOT, it) } ?: "–"
}
