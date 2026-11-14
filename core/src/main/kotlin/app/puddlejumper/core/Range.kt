package app.puddlejumper.core

/** The app shows distances in kilometres only. */
object Km {
    fun toMeters(km: Double) = km * 1000.0
    fun fromMeters(meters: Double) = meters / 1000.0
    fun format(meters: Double) = "%.0f km".format(fromMeters(meters))
}

/**
 * @param fullRangeMeters range on a 100% charge, set by the user.
 * @param reservePercent battery share the driver does not want to dip into.
 */
data class RangeSettings(
    val fullRangeMeters: Double,
    val reservePercent: Int = 10,
    val chargeToFullAtEachStop: Boolean = true,
    /** Range when setting off from stop 1 (the battery you have now). Null means a full charge. */
    val startRangeMeters: Double? = null,
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
     * Range left on arrival at each stop. Starts with [RangeSettings.startRangeMeters] (full when null). When [RangeSettings.chargeToFullAtEachStop]
     * is false, range carries over between legs (pass-through waypoints).
     */
    fun plan(legDistancesMeters: List<Double>, settings: RangeSettings): List<LegReport> {
        var range = settings.startRangeMeters ?: settings.fullRangeMeters
        return legDistancesMeters.mapIndexed { i, leg ->
            if (i > 0 && settings.chargeToFullAtEachStop) range = settings.fullRangeMeters
            val depart = range
            range = depart - leg
            LegReport(i, leg, depart, range, range >= settings.reserveMeters)
        }
    }
}
