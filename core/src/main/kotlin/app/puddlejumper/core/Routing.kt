package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class RouteStep(val instruction: String, val distanceMeters: Double, val location: LatLon)

data class RouteLeg(val distanceMeters: Double, val durationSeconds: Double, val steps: List<RouteStep>)

data class Route(val geometry: List<LatLon>, val legs: List<RouteLeg>) {
    val distanceMeters get() = legs.sumOf { it.distanceMeters }
    val durationSeconds get() = legs.sumOf { it.durationSeconds }
}

/**
 * OSRM routing. The public demo server (router.project-osrm.org) is for testing only;
 * swap [baseUrl] for your own server or replace this with Mapbox Directions for production.
 */
class OsrmRouter(private val baseUrl: String = "https://router.project-osrm.org") {

    fun routeUrl(waypoints: List<LatLon>): String {
        require(waypoints.size >= 2) { "Need at least two waypoints" }
        val coords = waypoints.joinToString(";") { "${it.lon},${it.lat}" }
        return "$baseUrl/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true"
    }

    fun parse(json: String): Route {
        val res = JSON.decodeFromString<Response>(json)
        if (res.code != "Ok" || res.routes.isEmpty()) {
            throw RoutingException(res.message ?: "No route found (${res.code})")
        }
        val r = res.routes.first()
        return Route(
            geometry = r.geometry.coordinates.map { LatLon(it[1], it[0]) },
            legs = r.legs.map { leg ->
                RouteLeg(
                    distanceMeters = leg.distance,
                    durationSeconds = leg.duration,
                    steps = leg.steps.map { s ->
                        RouteStep(
                            instruction = instructionText(s.maneuver.type, s.maneuver.modifier, s.name),
                            distanceMeters = s.distance,
                            location = LatLon(s.maneuver.location[1], s.maneuver.location[0]),
                        )
                    },
                )
            },
        )
    }

    @Serializable
    private data class Response(
        val code: String,
        val message: String? = null,
        val routes: List<OsrmRoute> = emptyList(),
    )

    @Serializable
    private data class OsrmRoute(val geometry: Geometry, val legs: List<Leg>)

    @Serializable
    private data class Geometry(val coordinates: List<List<Double>>)

    @Serializable
    private data class Leg(val distance: Double, val duration: Double, val steps: List<Step> = emptyList())

    @Serializable
    private data class Step(val distance: Double, val name: String = "", val maneuver: Maneuver)

    @Serializable
    private data class Maneuver(
        val type: String,
        val modifier: String? = null,
        val location: List<Double>,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun instructionText(type: String, modifier: String?, road: String): String {
            val onto = if (road.isBlank()) "" else " onto $road"
            val on = if (road.isBlank()) "" else " on $road"
            val dir = modifier?.let { " $it" } ?: ""
            return when (type) {
                "depart" -> "Head out$on"
                "arrive" -> "Arrive at stop"
                "turn", "end of road" -> "Turn$dir$onto"
                "merge" -> "Merge$dir$onto"
                "on ramp" -> "Take the ramp$dir$onto"
                "off ramp" -> "Take the exit$dir$onto"
                "fork" -> "Keep$dir at the fork$onto"
                "roundabout", "rotary" -> "Enter the roundabout, exit$onto"
                "continue", "new name" -> "Continue$dir$onto"
                else -> "Continue$on"
            }.replace("  ", " ")
        }
    }
}

class RoutingException(message: String) : Exception(message)

data class Place(val name: String, val location: LatLon)

/** Nominatim (OpenStreetMap) search. Usage policy: max 1 request/second and a real User-Agent. */
object NominatimGeocoder {
    fun searchUrl(query: String, limit: Int = 5): String =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=$limit&q=" +
            java.net.URLEncoder.encode(query, "UTF-8")

    fun reverseUrl(at: LatLon): String =
        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=${at.lat}&lon=${at.lon}"

    /** Short label for a reverse-geocoded point, e.g. "Main Street, Fresno". Null when nothing was found. */
    fun parseReverse(json: String): String? {
        val r = JSON.decodeFromString<Reverse>(json)
        r.name?.takeIf { it.isNotBlank() }?.let { return it }
        val a = r.address ?: return r.displayName?.split(",")?.take(2)?.joinToString(",")?.trim()
        val street = listOfNotNull(a.houseNumber, a.road).joinToString(" ").ifBlank { null }
        val town = a.city ?: a.town ?: a.village ?: a.county
        return listOfNotNull(street, town).joinToString(", ").ifBlank { r.displayName }
    }

    @Serializable
    private data class Reverse(
        val name: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val address: Address? = null,
    )

    @Serializable
    private data class Address(
        @SerialName("house_number") val houseNumber: String? = null,
        val road: String? = null,
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val county: String? = null,
    )

    fun parse(json: String): List<Place> =
        JSON.decodeFromString<List<Result>>(json).map { Place(it.displayName, LatLon(it.lat.toDouble(), it.lon.toDouble())) }

    @Serializable
    private data class Result(@SerialName("display_name") val displayName: String, val lat: String, val lon: String)

    private val JSON = Json { ignoreUnknownKeys = true }
}
