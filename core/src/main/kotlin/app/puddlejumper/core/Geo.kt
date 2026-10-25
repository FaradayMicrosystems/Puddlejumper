package app.puddlejumper.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Point reached by travelling [distanceM] from [start] on initial [bearingDeg]. */
    fun destination(start: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        val lonDeg = (Math.toDegrees(lon2) + 540) % 360 - 180
        return LatLon(Math.toDegrees(lat2), lonDeg)
    }

    /** Closed ring of points approximating a geodesic circle. */
    fun circle(center: LatLon, radiusM: Double, segments: Int = 90): List<LatLon> =
        (0..segments).map { destination(center, 360.0 * it / segments, radiusM) }

    fun bounds(points: List<LatLon>): BoundingBox {
        require(points.isNotEmpty())
        return BoundingBox(
            south = points.minOf { it.lat }, west = points.minOf { it.lon },
            north = points.maxOf { it.lat }, east = points.maxOf { it.lon },
        )
    }

    /** Keeps points at least [minSpacingM] apart (plus the last point). Used to shorten route geometry for API calls. */
    fun thin(points: List<LatLon>, minSpacingM: Double): List<LatLon> {
        if (points.size <= 2) return points
        val out = mutableListOf(points.first())
        for (p in points.subList(1, points.size - 1)) {
            if (distanceMeters(out.last(), p) >= minSpacingM) out += p
        }
        out += points.last()
        return out
    }
}
