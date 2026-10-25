package app.puddlejumper.core

enum class DistanceUnit(val metersPerUnit: Double, val label: String) {
    MILES(1609.344, "mi"),
    KILOMETERS(1000.0, "km");

    fun toMeters(value: Double) = value * metersPerUnit
    fun fromMeters(meters: Double) = meters / metersPerUnit
    fun format(meters: Double) = "%.0f %s".format(fromMeters(meters), label)
}

/**
 * @param fullRangeMeters range on a 100% charge, set by the user.
 * @param reservePercent battery share the driver does not want to dip into.
 */
data class RangeSettings(
    val fullRangeMeters: Double,
    val reservePercent: Int = 10,
    val chargeToFullAtEachStop: Boolean = true,
) {
    init {
        require(fullRangeMeters > 0) { "fullRangeMeters must be positive" }
        require(reservePercent in 0..90) { "reservePercent must be 0..90" }
    }

    val reserveMeters: Double get() = fullRangeMeters * reservePercent / 100.0
}

data class LegReport(
    val legIndex: Int,
    val distanceMeters: Double,
    val departRangeMeters: Double,
    val arriveRangeMeters: Double,
    /** True when the driver arrives with at least the reserve left. */
    val reachable: Boolean,
)

object RangePlanner {
    /**
     * Range left on arrival at each stop. Starts on a full charge. When [RangeSettings.chargeToFullAtEachStop]
     * is false, range carries over between legs (pass-through waypoints).
     */
    fun plan(legDistancesMeters: List<Double>, settings: RangeSettings): List<LegReport> {
        var range = settings.fullRangeMeters
        return legDistancesMeters.mapIndexed { i, leg ->
            if (i > 0 && settings.chargeToFullAtEachStop) range = settings.fullRangeMeters
            val depart = range
            range = depart - leg
            LegReport(i, leg, depart, range, range >= settings.reserveMeters)
        }
    }
}
