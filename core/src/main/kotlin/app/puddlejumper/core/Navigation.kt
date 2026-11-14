package app.puddlejumper.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One GPS reading. */
data class NavFix(
    val location: LatLon,
    val timeMs: Long,
    val accuracyM: Double = 10.0,
    val bearingDeg: Double? = null,
    val speedMps: Double? = null,
)

/** Where the driver is on the route, and what to show and say. */
data class NavProgress(
    /** The fix moved onto the route line. */
    val snapped: LatLon,
    /** How far the fix is from the route line. */
    val distanceFromRouteM: Double,
    /** Leg being driven (0 = towards stop 2). */
    val legIndex: Int,
    /** Index into [NavigationEngine.steps]. */
    val stepIndex: Int,
    /** Distance to the next manoeuvre (end of the current step). */
    val distanceToManeuverM: Double,
    /** Banner for the next manoeuvre. */
    val banner: BannerInstruction?,
    val distanceToNextStopM: Double,
    val distanceRemainingM: Double,
    val durationRemainingS: Double,
    /** A prompt to say now, or null. Each prompt is given once. */
    val speak: String?,
    /** True when the driver has left the route and a new one should be fetched. */
    val offRoute: Boolean,
    /** Index of the leg that was just finished on this fix, or null. Given once per leg. */
    val reachedStopOfLeg: Int?,
    /** True once the last stop is reached. */
    val arrived: Boolean,
)

data class NavConfig(
    /** Off route when further than this from the line (plus GPS accuracy, see [offRouteThreshold]). */
    val offRouteBaseM: Double = 50.0,
    /** Readings in a row that must be off the line before a reroute (stops one bad fix rerouting). */
    val offRouteFixes: Int = 3,
    /** Readings this far off count as off route at once. */
    val offRouteImmediateM: Double = 250.0,
    /** A stop counts as reached within this distance of it, measured along the route. */
    val arriveRadiusM: Double = 30.0,
    /** How far back and ahead of the last position to look for the car, so loops and parallel roads don't confuse it. */
    val searchBackM: Double = 200.0,
    val searchAheadM: Double = 3_000.0,
)

/**
 * Follows the car along a [Route]: snaps GPS fixes to the line, finds the next manoeuvre, decides when to
 * speak each prompt, and detects when the driver has left the route. Pure Kotlin, no Android, so it is unit-tested.
 */
class NavigationEngine(val route: Route, private val config: NavConfig = NavConfig()) {

    /** All steps of all legs, in driving order. */
    val steps: List<RouteStep> = route.legs.flatMap { it.steps }
    private val stepLeg: IntArray = route.legs.flatMapIndexed { li, leg -> List(leg.steps.size) { li } }.toIntArray()

    private val points: List<LatLon>
    /** Distance along the line at each point. */
    private val along: DoubleArray
    /** Distance along the line where each step starts. */
    private val stepStart: DoubleArray
    /** Distance along the line where each leg ends. */
    private val legEnd: DoubleArray
    val lengthM: Double

    init {
        require(route.legs.isNotEmpty()) { "Route has no legs" }
        val haveStepGeometry = steps.isNotEmpty() && steps.all { it.geometry.isNotEmpty() } && route.legs.all { it.steps.isNotEmpty() }
        val pts = ArrayList<LatLon>()
        val starts = DoubleArray(steps.size)
        if (haveStepGeometry) {
            var cum = 0.0
            steps.forEachIndexed { i, st ->
                val first = st.geometry.first()
                if (pts.isNotEmpty() && first != pts.last()) { cum += Geo.distanceMeters(pts.last(), first); pts += first }
                if (pts.isEmpty()) pts += first
                // A step starts at its first point, which is normally the previous step's last point.
                starts[i] = cum
                for (p in st.geometry.drop(1)) {
                    cum += Geo.distanceMeters(pts.last(), p)
                    pts += p
                }
            }
        } else {
            pts += route.geometry
        }
        require(pts.isNotEmpty()) { "Route has no geometry" }
        if (pts.size == 1) pts += pts[0]
        points = pts
        along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + Geo.distanceMeters(pts[i - 1], pts[i])
        lengthM = along.last()

        if (haveStepGeometry) {
            stepStart = starts
            legEnd = DoubleArray(route.legs.size) { li ->
                val nextFirst = stepLeg.indexOfFirst { it == li + 1 }
                if (nextFirst < 0) lengthM else stepStart[nextFirst]
            }
        } else {
            // No per-step lines: spread the steps and legs over the line by their stated distances.
            val stated = route.legs.sumOf { it.distanceMeters }.takeIf { it > 0 } ?: 1.0
            val scale = lengthM / stated
            var legBase = 0.0
            var si = 0
            stepStart = DoubleArray(steps.size)
            legEnd = DoubleArray(route.legs.size)
            route.legs.forEachIndexed { li, leg ->
                var d = legBase
                for (st in leg.steps) {
                    stepStart[si++] = min(d, lengthM)
                    d += st.distanceMeters * scale
                }
                legBase += leg.distanceMeters * scale
                legEnd[li] = min(legBase, lengthM)
            }
            legEnd[legEnd.size - 1] = lengthM
        }
    }

    private var lastAlong: Double? = null
    private var offCount = 0
    private val spoken = HashSet<Pair<Int, Int>>()
    private var legsReached = 0
    private var arrived = false

    fun update(fix: NavFix): NavProgress {
        val (dist, pos, snapped) = snap(fix.location)
        val threshold = offRouteThreshold(fix.accuracyM)
        offCount = if (dist > threshold) offCount + 1 else 0
        val offRoute = !arrived && (offCount >= config.offRouteFixes || dist > config.offRouteImmediateM + min(fix.accuracyM, 100.0))
        // Off the line: don't move progress along the route on a bad reading.
        if (!offRoute && offCount == 0) lastAlong = pos
        val a = lastAlong ?: pos

        val stepIndex = stepAt(a)
        val stepEnd = if (stepIndex + 1 < steps.size) stepStart[stepIndex + 1] else lengthM
        val toManeuver = max(0.0, stepEnd - a)
        val legIndex = legAt(a)

        var reached: Int? = null
        if (!offRoute && legsReached < route.legs.size && a >= legEnd[legsReached] - config.arriveRadiusM) {
            reached = legsReached
            legsReached++
            if (legsReached == route.legs.size) arrived = true
        }

        val speak = if (offRoute || steps.isEmpty()) null else promptFor(stepIndex, toManeuver)

        return NavProgress(
            snapped = snapped,
            distanceFromRouteM = dist,
            legIndex = legIndex,
            stepIndex = stepIndex,
            distanceToManeuverM = toManeuver,
            banner = steps.getOrNull(stepIndex)?.let { bannerFor(it, toManeuver) },
            distanceToNextStopM = max(0.0, legEnd[legIndex] - a),
            distanceRemainingM = max(0.0, lengthM - a),
            durationRemainingS = durationRemaining(stepIndex, toManeuver, a),
            speak = speak,
            offRoute = offRoute,
            reachedStopOfLeg = reached,
            arrived = arrived,
        )
    }

    fun offRouteThreshold(accuracyM: Double) = config.offRouteBaseM + min(max(accuracyM, 0.0), 100.0)

    private fun stepAt(a: Double): Int {
        if (steps.isEmpty()) return 0
        var i = 0
        while (i + 1 < steps.size && stepStart[i + 1] <= a + 0.5) i++
        return i
    }

    private fun legAt(a: Double): Int {
        for (i in legEnd.indices) if (a < legEnd[i] - config.arriveRadiusM) return i
        return legEnd.size - 1
    }

    /** The prompt most recently passed in this step, if not yet said. Earlier ones that were skipped are dropped. */
    private fun promptFor(stepIndex: Int, toManeuver: Double): String? {
        val voice = steps[stepIndex].voice
        var best = -1
        for ((i, v) in voice.withIndex()) {
            if (v.distanceBeforeEndM + 1.0 >= toManeuver && (best < 0 || v.distanceBeforeEndM < voice[best].distanceBeforeEndM)) best = i
        }
        if (best < 0 || (stepIndex to best) in spoken) return null
        // Mark this one and every longer-distance one as said.
        for ((i, v) in voice.withIndex()) if (v.distanceBeforeEndM >= voice[best].distanceBeforeEndM) spoken += stepIndex to i
        return voice[best].text
    }

    private fun bannerFor(step: RouteStep, toManeuver: Double): BannerInstruction? {
        val shown = step.banners.filter { it.distanceBeforeEndM + 1.0 >= toManeuver }
        return shown.minByOrNull { it.distanceBeforeEndM } ?: step.banners.maxByOrNull { it.distanceBeforeEndM }
    }

    private fun durationRemaining(stepIndex: Int, toManeuver: Double, a: Double): Double {
        val haveStepTimes = steps.isNotEmpty() && steps.any { it.durationSeconds > 0 }
        if (!haveStepTimes || lengthM <= 0) return route.durationSeconds * max(0.0, lengthM - a) / max(lengthM, 1.0)
        val step = steps[stepIndex]
        val stepLen = (if (stepIndex + 1 < steps.size) stepStart[stepIndex + 1] else lengthM) - stepStart[stepIndex]
        val frac = if (stepLen > 0) toManeuver / stepLen else 0.0
        return step.durationSeconds * frac + steps.drop(stepIndex + 1).sumOf { it.durationSeconds }
    }

    /** Nearest point on the line: (distance from the line, distance along it, the point). */
    private fun snap(p: LatLon): Triple<Double, Double, LatLon> {
        val last = lastAlong
        val local = if (last == null) null else nearest(p, last - config.searchBackM, last + config.searchAheadM)
        if (local != null && local.first <= offRouteThreshold(0.0) * 2) return local
        val global = nearest(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)!!
        return if (local == null || global.first < local.first - 1.0) global else local
    }

    private fun nearest(p: LatLon, from: Double, to: Double): Triple<Double, Double, LatLon>? {
        var best: Triple<Double, Double, LatLon>? = null
        val kx = cos(Math.toRadians(p.lat)) * M_PER_DEG
        for (i in 0 until points.size - 1) {
            if (along[i + 1] < from || along[i] > to) continue
            val a = points[i]
            val b = points[i + 1]
            // Flat projection around p; fine for segments of a few km.
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val cx = ax + tc * dx; val cy = ay + tc * dy
            val d = sqrt(cx * cx + cy * cy)
            if (best == null || d < best.first) {
                val snapped = LatLon(a.lat + tc * (b.lat - a.lat), a.lon + tc * (b.lon - a.lon))
                best = Triple(d, along[i] + tc * (along[i + 1] - along[i]), snapped)
            }
        }
        return best
    }

    private companion object {
        const val M_PER_DEG = Geo.EARTH_RADIUS_M * Math.PI / 180
    }
}

/** Limits how often a new route is fetched after leaving the old one. */
class RerouteGate(private val minIntervalMs: Long = 10_000) {
    private var lastMs: Long? = null
    private var busy = false

    /** True when a reroute may start now; the caller must call [done] when it finishes. */
    fun tryStart(nowMs: Long): Boolean {
        if (busy) return false
        lastMs?.let { if (nowMs - it < minIntervalMs) return false }
        busy = true
        lastMs = nowMs
        return true
    }

    fun done() { busy = false }
}
