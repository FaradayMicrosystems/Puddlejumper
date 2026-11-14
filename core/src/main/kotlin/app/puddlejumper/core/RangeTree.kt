package app.puddlejumper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale

/**
 * The car, as the energy model sees it. All metric.
 *
 * @param usableKwh usable battery capacity.
 * @param kwhPer100Km the driver's average consumption on flat roads (includes speed, heating, etc.).
 * @param massKg car plus people; used for the energy of climbing and descending.
 * @param drivetrainEfficiency share of battery energy that becomes height when climbing.
 * @param regenEfficiency share of height energy won back by regenerative braking when descending.
 */
data class EvModel(
    val usableKwh: Double,
    val kwhPer100Km: Double,
    val massKg: Double = 2_100.0,
    val drivetrainEfficiency: Double = 0.9,
    val regenEfficiency: Double = 0.6,
) {
    init {
        require(usableKwh > 0) { "usableKwh must be positive" }
        require(kwhPer100Km > 0) { "kwhPer100Km must be positive" }
        require(massKg > 0) { "massKg must be positive" }
    }

    /** Range on a full battery on flat roads. */
    val fullRangeMeters: Double get() = usableKwh / kwhPer100Km * 100_000.0

    /** Energy for [meters] of flat road. */
    fun flatKwh(meters: Double) = kwhPer100Km * meters / 100_000.0

    /** Extra energy for a height change of [dhMeters]; negative (energy won back) when going down. */
    fun climbKwh(dhMeters: Double): Double {
        val kwh = massKg * G * dhMeters / J_PER_KWH
        return if (dhMeters >= 0) kwh / drivetrainEfficiency else kwh * regenEfficiency
    }

    /** Energy available above the reserve, for a battery at [batteryPercent]. Never negative. */
    fun budgetKwh(batteryPercent: Double, reservePercent: Int) =
        (usableKwh * (batteryPercent - reservePercent) / 100.0).coerceAtLeast(0.0)

    /** Battery percentage used by [kwh]. */
    fun percentOf(kwh: Double) = kwh / usableKwh * 100.0

    companion object {
        const val G = 9.81
        const val J_PER_KWH = 3_600_000.0
    }
}

/** A road line with the distance from its start to each point. */
class Path(val points: List<LatLon>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
    }
    val lengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Point [d] metres along the line (clamped to its ends). */
    fun pointAt(d: Double): LatLon {
        if (points.isEmpty()) throw IllegalStateException("Empty path")
        if (d <= 0) return points.first()
        if (d >= lengthMeters) return points.last()
        var i = cumulative.indexOfFirst { it >= d }
        if (i <= 0) i = 1
        val seg = cumulative[i] - cumulative[i - 1]
        val t = if (seg > 0) (d - cumulative[i - 1]) / seg else 0.0
        return lerp(points[i - 1], points[i], t)
    }

    companion object {
        fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }
}

/** One branch of the tree: the road from the origin towards one compass bearing, cut where the battery runs out. */
data class TreeBranch(
    val bearingDeg: Double,
    val points: List<LatLon>,
    val roadMeters: Double,
    /** True when the battery ran out on this road; false when the road ended first (coast, dead end). */
    val batteryLimited: Boolean,
)

/**
 * The range tree: roads from where you are, out in every direction, each drawn as far as the battery
 * (above the reserve) gets you. Energy uses the driver's average consumption plus terrain: climbs cost
 * extra, descents win some back.
 *
 * How it is built: one route per compass bearing from the origin to a point well beyond the flat range,
 * then each route is cut where its energy use reaches the budget ([clip]). Routes share roads near the
 * origin, so together they look like a tree.
 */
object RangeTree {
    /** Bearings the tree fans out on (every 15 degrees). */
    val BEARINGS: List<Double> = List(24) { it * 15.0 }

    /**
     * Route targets: straight-line [reachMeters] out on each bearing. Roads wind, so a route to a target
     * at the flat range is longer than the range and gets cut ([clip]).
     */
    fun targets(origin: LatLon, reachMeters: Double): List<LatLon> =
        BEARINGS.map { Geo.destination(origin, it, reachMeters) }

    /**
     * Points every [spacingMeters] along [path] (from its start, up to [maxMeters]) where we want an elevation.
     * Returned with their distance along the path.
     */
    fun elevationSamples(path: Path, spacingMeters: Double, maxMeters: Double): List<Pair<Double, LatLon>> {
        val end = minOf(path.lengthMeters, maxMeters)
        val out = mutableListOf<Pair<Double, LatLon>>()
        var d = 0.0
        while (d < end) {
            out += d to path.pointAt(d)
            d += spacingMeters
        }
        out += end to path.pointAt(end)
        return out
    }

    /**
     * Cuts [path] where the energy used reaches [budgetKwh].
     *
     * @param elevation height (m) at distances along the path, sorted by distance. Empty means flat.
     *   Between samples height is interpolated; beyond the last one it stays level.
     */
    fun clip(
        bearingDeg: Double,
        path: Path,
        elevation: List<Pair<Double, Double>>,
        model: EvModel,
        budgetKwh: Double,
    ): TreeBranch {
        val pts = path.points
        if (pts.size < 2 || budgetKwh <= 0) return TreeBranch(bearingDeg, pts.take(1), 0.0, true)
        val out = mutableListOf(pts.first())
        var used = 0.0
        var prevH = heightAt(elevation, 0.0)
        for (i in 1 until pts.size) {
            val d0 = path.cumulative[i - 1]
            val d1 = path.cumulative[i]
            val h1 = heightAt(elevation, d1)
            val step = model.flatKwh(d1 - d0) + model.climbKwh(h1 - prevH)
            if (used + step >= budgetKwh && step > 0) {
                val t = ((budgetKwh - used) / step).coerceIn(0.0, 1.0)
                out += Path.lerp(pts[i - 1], pts[i], t)
                return TreeBranch(bearingDeg, out, d0 + (d1 - d0) * t, true)
            }
            used += step
            prevH = h1
            out += pts[i]
        }
        return TreeBranch(bearingDeg, out, path.lengthMeters, false)
    }

    private fun heightAt(samples: List<Pair<Double, Double>>, d: Double): Double {
        if (samples.isEmpty()) return 0.0
        if (d <= samples.first().first) return samples.first().second
        if (d >= samples.last().first) return samples.last().second
        // Binary search for the sample pair around d.
        var lo = 0
        var hi = samples.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (samples[mid].first <= d) lo = mid else hi = mid
        }
        val (da, ha) = samples[lo]
        val (db, hb) = samples[hi]
        return if (db > da) ha + (hb - ha) * (d - da) / (db - da) else ha
    }
}

/**
 * Plain road lines for the range tree: no turn instructions, compact polyline6 geometry.
 * [osrm] is the free test server; [mapbox] is used when the build has a token.
 */
class BranchRouter private constructor(
    private val urlFor: (LatLon, LatLon) -> String,
    /** Pause between requests, to respect the server's usage policy. */
    val requestGapMs: Long,
) {
    fun url(from: LatLon, to: LatLon) = urlFor(from, to)

    /** The road line, or null when there is no road to that target. */
    fun parse(json: String): Path? {
        val res = JSON.decodeFromString<Response>(json)
        val g = res.routes.firstOrNull()?.geometry ?: return null
        if (res.code != "Ok") return null
        return Path(Polyline.decode(g, 6))
    }

    @Serializable
    private data class Response(val code: String? = null, val routes: List<R> = emptyList())

    @Serializable
    private data class R(val geometry: String? = null)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun coords(a: LatLon, b: LatLon) =
            "%.5f,%.5f;%.5f,%.5f".format(Locale.ROOT, a.lon, a.lat, b.lon, b.lat)

        fun osrm(baseUrl: String = "https://router.project-osrm.org") = BranchRouter(
            { a, b -> "$baseUrl/route/v1/driving/${coords(a, b)}?overview=full&geometries=polyline6&steps=false" },
            requestGapMs = 1_100,
        )

        fun mapbox(token: String, baseUrl: String = "https://api.mapbox.com") = BranchRouter(
            { a, b ->
                "$baseUrl/directions/v5/mapbox/driving/${coords(a, b)}?overview=full&geometries=polyline6" +
                    "&alternatives=false&steps=false&access_token=" + URLEncoder.encode(token, "UTF-8")
            },
            requestGapMs = 250,
        )
    }
}

/** Google encoded polyline format (precision 5 or 6), as sent by OSRM and Mapbox. */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            for (k in 0..1) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    b = encoded[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += delta else lon += delta
            }
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }
}

/**
 * Open-Meteo Elevation API (open-meteo.com/en/docs/elevation-api): free, no key, 90 m terrain data,
 * up to 100 points per request.
 */
object OpenMeteoElevation {
    const val MAX_POINTS = 100

    fun url(points: List<LatLon>): String {
        require(points.size in 1..MAX_POINTS) { "1..$MAX_POINTS points per request" }
        val lat = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.lat) }
        val lon = points.joinToString(",") { "%.4f".format(Locale.ROOT, it.lon) }
        return "https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon"
    }

    /** Heights in metres, in request order. */
    fun parse(json: String): List<Double?> = JSON.decodeFromString<Response>(json).elevation

    /** Cache key: about 1 km grid, so nearby samples from different branches share one lookup. */
    fun key(p: LatLon) = "%.2f,%.2f".format(Locale.ROOT, p.lat, p.lon)

    @Serializable
    private data class Response(val elevation: List<Double?> = emptyList())

    private val JSON = Json { ignoreUnknownKeys = true }
}
