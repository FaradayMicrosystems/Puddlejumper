package app.puddlejumper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.cos

/**
 * How far the road network lets you go from one stop, by compass direction.
 *
 * A straight-line circle overstates range: roads wind, and water or mountains block whole directions.
 * This profile stores, for each bearing, the share of a road distance that turns into straight-line
 * distance ([ratio], 0..1), plus a hard limit ([capMeters]) where the samples found no road (coast, lake,
 * wilderness). [polygon] turns it into a reachable area for any range, so moving the range slider redraws
 * instantly without new network calls.
 *
 * @param sampledRangeMeters the range the samples were taken for. Caps are only trusted near this range.
 */
data class ReachProfile(
    val center: LatLon,
    val sampledRangeMeters: Double,
    val bearingsDeg: List<Double>,
    val ratio: List<Double>,
    val capMeters: List<Double?>,
) {
    init {
        require(bearingsDeg.size == ratio.size && ratio.size == capMeters.size) { "Profile lists differ in size" }
    }

    /** True when the profile is still a good fit for [rangeMeters] (within ±25% of the sampled range). */
    fun fits(rangeMeters: Double) = abs(rangeMeters - sampledRangeMeters) <= sampledRangeMeters * REFETCH_SHARE

    /** Straight-line reach along each bearing for a road range of [rangeMeters]. */
    fun radii(rangeMeters: Double): List<Double> {
        // Caps mark where roads stop (e.g. a coastline). For much larger ranges (multi-jump rings)
        // there may be land beyond, so only use caps near the sampled range.
        val useCaps = rangeMeters <= sampledRangeMeters * (1 + REFETCH_SHARE)
        return ratio.indices.map { i ->
            val r = rangeMeters * ratio[i]
            val cap = capMeters[i]
            if (useCaps && cap != null) minOf(r, cap) else r
        }
    }

    /** Closed ring around [center] of the area reachable by road with [rangeMeters]. */
    fun polygon(rangeMeters: Double): List<LatLon> {
        val ring = radii(rangeMeters).mapIndexed { i, r -> Geo.destination(center, bearingsDeg[i], r) }
        return ring + ring.first()
    }

    companion object {
        const val REFETCH_SHARE = 0.25
    }
}

/**
 * Builds a [ReachProfile] from one OSRM `table` request: road distances from the stop to points sampled
 * on rings around it. Works at any EV range and needs no key (the isochrone APIs we could use cap out
 * well below EV ranges: Mapbox at 100 km, the public Valhalla server at 200 km).
 *
 * Swap [baseUrl] for your own OSRM server, or replace with the Mapbox Matrix API, for production.
 */
class RoadReachSampler(
    private val baseUrl: String = "https://router.project-osrm.org",
    val bearingCount: Int = 32,
    /** Sample rings as shares of the range. Two rings handle gaps (a lake) between the stop and the edge. */
    val ringShares: List<Double> = listOf(0.45, 0.8),
) {
    init {
        // The public OSRM server caps table requests at 100 coordinates (1 source + samples).
        require(bearingCount * ringShares.size + 1 <= 100) { "Too many samples for one table request" }
    }

    val bearingsDeg: List<Double> = List(bearingCount) { 360.0 * it / bearingCount }

    /** Sample points in request order: ring by ring, bearing by bearing. */
    fun samples(center: LatLon, rangeMeters: Double): List<LatLon> =
        ringShares.flatMap { share -> bearingsDeg.map { Geo.destination(center, it, rangeMeters * share) } }

    fun tableUrl(center: LatLon, rangeMeters: Double): String {
        val coords = (listOf(center) + samples(center, rangeMeters))
            .joinToString(";") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lon, it.lat) }
        return "$baseUrl/table/v1/driving/$coords?sources=0&annotations=distance"
    }

    /** Throws [RoutingException] when no sample could be reached by road. */
    fun parse(json: String, center: LatLon, rangeMeters: Double): ReachProfile {
        val res = JSON.decodeFromString<TableResponse>(json)
        if (res.code != "Ok") throw RoutingException(res.message ?: "Range lookup failed (${res.code})")
        val roads = res.distances?.firstOrNull() ?: throw RoutingException("Range lookup returned no distances")
        val n = bearingCount
        val ratio = arrayOfNulls<Double>(n)
        val cap = arrayOfNulls<Double>(n)

        for (b in 0 until n) {
            // Walk outward; the outermost sample with a road distance sets the ratio.
            var best: Double? = null
            var outerCap: Double? = null
            ringShares.forEachIndexed { ring, share ->
                val j = 1 + ring * n + b // index 0 is the stop itself
                val road = roads.getOrNull(j) ?: return@forEachIndexed
                val dest = res.destinations.getOrNull(j) ?: return@forEachIndexed
                val snapped = LatLon(dest.location[1], dest.location[0])
                val straight = Geo.distanceMeters(center, snapped)
                if (straight < MIN_SAMPLE_M || road <= 0) return@forEachIndexed
                best = (straight / road).coerceIn(MIN_RATIO, 1.0)
                val sampleRadius = rangeMeters * share
                val offRoad = dest.distance > maxOf(MIN_SNAP_M, sampleRadius * OFF_ROAD_SHARE)
                // Only the outermost ring decides the cap: on-road there means no cap.
                if (ring == ringShares.lastIndex) {
                    outerCap = if (offRoad) {
                        val off = Math.toRadians(Geo.initialBearing(center, snapped) - bearingsDeg[b])
                        (straight * cos(off)).coerceAtLeast(0.0)
                    } else null
                }
            }
            ratio[b] = best
            cap[b] = outerCap
        }

        if (ratio.all { it == null }) throw RoutingException("No roads found around this stop")
        return ReachProfile(center, rangeMeters, bearingsDeg, fillGaps(ratio), cap.toList())
    }

    /** Bearings with no usable sample take the mean of their nearest known neighbours on each side. */
    private fun fillGaps(values: Array<Double?>): List<Double> {
        val n = values.size
        return List(n) { i ->
            values[i] ?: run {
                val left = (1 until n).firstNotNullOf { values[(i - it + n) % n] }
                val right = (1 until n).firstNotNullOf { values[(i + it) % n] }
                (left + right) / 2
            }
        }
    }

    @Serializable
    private data class TableResponse(
        val code: String,
        val message: String? = null,
        val distances: List<List<Double?>>? = null,
        val destinations: List<Waypoint> = emptyList(),
    )

    /** [distance] is how far OSRM moved the point to reach the nearest road, in metres. */
    @Serializable
    private data class Waypoint(val location: List<Double>, val distance: Double = 0.0)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val MIN_SAMPLE_M = 1_000.0
        /** Roads are never more than 5x longer than the straight line in practice; guards bad data. */
        private const val MIN_RATIO = 0.2
        /** A sample snapped this far to find a road is treated as "no road here". */
        private const val MIN_SNAP_M = 2_000.0
        private const val OFF_ROAD_SHARE = 0.15
    }
}
