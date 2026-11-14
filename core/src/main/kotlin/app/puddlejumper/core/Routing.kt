package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** What the driver does at the start of a step, e.g. type "turn", modifier "left". Used to pick an arrow. */
data class Maneuver(val type: String, val modifier: String? = null)

/**
 * Something to say while driving a step. [distanceBeforeEndM] is how far before the end of the step
 * (where the next manoeuvre is) to say it.
 */
data class VoiceInstruction(val distanceBeforeEndM: Double, val text: String)

/** Next-turn banner shown while driving a step, from [distanceBeforeEndM] before its end. */
data class BannerInstruction(
    val distanceBeforeEndM: Double,
    val text: String,
    val maneuver: Maneuver,
    val secondary: String? = null,
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    /** Where the step's own manoeuvre happens (its start). */
    val location: LatLon,
    val durationSeconds: Double = 0.0,
    /** The road this step follows, from start to end. Empty when the router did not send it. */
    val geometry: List<LatLon> = emptyList(),
    val maneuver: Maneuver = Maneuver("continue"),
    val road: String = "",
    /** Spoken prompts for the manoeuvre at the end of this step. */
    val voice: List<VoiceInstruction> = emptyList(),
    /** Banners for the manoeuvre at the end of this step. */
    val banners: List<BannerInstruction> = emptyList(),
)

data class RouteLeg(val distanceMeters: Double, val durationSeconds: Double, val steps: List<RouteStep>)

data class Route(val geometry: List<LatLon>, val legs: List<RouteLeg>) {
    val distanceMeters get() = legs.sumOf { it.distanceMeters }
    val durationSeconds get() = legs.sumOf { it.durationSeconds }
}

/** A routing service: builds the request URL and parses the answer. */
interface Router {
    val name: String

    /**
     * URL for a route through [waypoints]. [headingDeg] is the direction the car is moving at the first
     * waypoint, so a reroute does not start with a U-turn. Routers that cannot use it ignore it.
     */
    fun routeUrl(waypoints: List<LatLon>, headingDeg: Double? = null): String

    fun parse(json: String): Route
}

/**
 * OSRM routing. The public demo server (router.project-osrm.org) is for testing only and can stop
 * without notice; the app uses it only when the build has no Mapbox token.
 * OSRM sends no spoken prompts, so they are made from the step instructions ([Guidance.withPrompts]).
 */
class OsrmRouter(private val baseUrl: String = "https://router.project-osrm.org") : Router {

    override val name = "OSRM demo server"

    override fun routeUrl(waypoints: List<LatLon>, headingDeg: Double?): String {
        require(waypoints.size >= 2) { "Need at least two waypoints" }
        val coords = waypoints.joinToString(";") { "${it.lon},${it.lat}" }
        return "$baseUrl/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true"
    }

    override fun parse(json: String): Route {
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
                    steps = Guidance.withPrompts(
                        leg.steps.map { s ->
                            RouteStep(
                                instruction = instructionText(s.maneuver.type, s.maneuver.modifier, s.name),
                                distanceMeters = s.distance,
                                location = LatLon(s.maneuver.location[1], s.maneuver.location[0]),
                                durationSeconds = s.duration,
                                geometry = s.geometry?.coordinates?.map { LatLon(it[1], it[0]) }.orEmpty(),
                                maneuver = Maneuver(s.maneuver.type, s.maneuver.modifier),
                                road = s.name,
                            )
                        }
                    ),
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
    private data class Step(
        val distance: Double,
        val duration: Double = 0.0,
        val name: String = "",
        val geometry: Geometry? = null,
        val maneuver: OsrmManeuver,
    )

    @Serializable
    private data class OsrmManeuver(
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

/**
 * Mapbox Directions API (https://docs.mapbox.com/api/navigation/directions/). Sends live-traffic routes
 * with spoken prompts and banners, in metric units. Needs a public access token (pk.…).
 */
class MapboxRouter(
    private val accessToken: String,
    private val baseUrl: String = "https://api.mapbox.com",
    private val language: String = "en",
) : Router {

    override val name = "Mapbox"

    override fun routeUrl(waypoints: List<LatLon>, headingDeg: Double?): String {
        require(waypoints.size >= 2) { "Need at least two waypoints" }
        require(waypoints.size <= MAX_WAYPOINTS) { "Mapbox routes allow at most $MAX_WAYPOINTS stops" }
        // Live traffic allows only 3 waypoints per request; longer trips use the normal driving profile.
        val profile = if (waypoints.size <= MAX_TRAFFIC_WAYPOINTS) "driving-traffic" else "driving"
        val coords = waypoints.joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lon, it.lat) }
        val params = buildList {
            add("alternatives=false")
            add("geometries=geojson")
            add("overview=full")
            add("steps=true")
            add("voice_instructions=true")
            add("banner_instructions=true")
            add("voice_units=metric")
            add("language=$language")
            if (headingDeg != null) {
                val first = "${((headingDeg % 360 + 360) % 360).roundToInt() % 360},45"
                add("bearings=" + (listOf(first) + List(waypoints.size - 1) { "" }).joinToString(";"))
            }
            add("access_token=" + URLEncoder.encode(accessToken, "UTF-8"))
        }
        return "$baseUrl/directions/v5/mapbox/$profile/$coords?" + params.joinToString("&")
    }

    override fun parse(json: String): Route {
        val res = JSON.decodeFromString<Response>(json)
        if (res.code != "Ok" || res.routes.isEmpty()) {
            throw RoutingException(res.message ?: "No route found (${res.code ?: "no answer"})")
        }
        val r = res.routes.first()
        return Route(
            geometry = r.geometry.coordinates.map { LatLon(it[1], it[0]) },
            legs = r.legs.map { leg ->
                RouteLeg(
                    distanceMeters = leg.distance,
                    durationSeconds = leg.duration,
                    steps = leg.steps.map { s ->
                        val m = Maneuver(s.maneuver.type, s.maneuver.modifier)
                        RouteStep(
                            instruction = s.maneuver.instruction
                                ?: OsrmRouter.instructionText(m.type, m.modifier, s.name),
                            distanceMeters = s.distance,
                            location = LatLon(s.maneuver.location[1], s.maneuver.location[0]),
                            durationSeconds = s.duration,
                            geometry = s.geometry?.coordinates?.map { LatLon(it[1], it[0]) }.orEmpty(),
                            maneuver = m,
                            road = s.name,
                            voice = s.voiceInstructions.map { VoiceInstruction(it.distanceAlongGeometry, it.announcement) },
                            banners = s.bannerInstructions.map {
                                BannerInstruction(
                                    distanceBeforeEndM = it.distanceAlongGeometry,
                                    text = it.primary.text,
                                    maneuver = Maneuver(it.primary.type ?: "turn", it.primary.modifier),
                                    secondary = it.secondary?.text,
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    @Serializable
    private data class Response(
        val code: String? = null,
        val message: String? = null,
        val routes: List<MbRoute> = emptyList(),
    )

    @Serializable
    private data class MbRoute(val geometry: Geometry, val legs: List<Leg>)

    @Serializable
    private data class Geometry(val coordinates: List<List<Double>>)

    @Serializable
    private data class Leg(val distance: Double, val duration: Double, val steps: List<Step> = emptyList())

    @Serializable
    private data class Step(
        val distance: Double,
        val duration: Double = 0.0,
        val name: String = "",
        val geometry: Geometry? = null,
        val maneuver: MbManeuver,
        val voiceInstructions: List<Voice> = emptyList(),
        val bannerInstructions: List<Banner> = emptyList(),
    )

    @Serializable
    private data class MbManeuver(
        val type: String,
        val modifier: String? = null,
        val instruction: String? = null,
        val location: List<Double>,
    )

    @Serializable
    private data class Voice(val distanceAlongGeometry: Double, val announcement: String)

    @Serializable
    private data class Banner(
        val distanceAlongGeometry: Double,
        val primary: BannerText,
        val secondary: BannerText? = null,
    )

    @Serializable
    private data class BannerText(val text: String, val type: String? = null, val modifier: String? = null)

    companion object {
        const val MAX_WAYPOINTS = 25
        const val MAX_TRAFFIC_WAYPOINTS = 3
        private val JSON = Json { ignoreUnknownKeys = true }
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
