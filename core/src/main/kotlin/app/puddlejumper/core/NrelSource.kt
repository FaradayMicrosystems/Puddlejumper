package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * NREL Alternative Fuels Data Center (US + Canada). Free key from developer.nrel.gov; "DEMO_KEY" works for light testing.
 * Docs: https://developer.nrel.gov/docs/transportation/alt-fuel-stations-v1/
 */
class NrelSource(private val apiKey: String) : ChargerSource {
    override val name = "NREL AFDC"

    override fun nearbyUrl(center: LatLon, radiusMeters: Double, limit: Int): String {
        val miles = (radiusMeters / 1609.344).coerceIn(1.0, 500.0)
        return "$BASE/nearest.json?api_key=${enc(apiKey)}&fuel_type=ELEC&status=E,T,P&access=public" +
            "&latitude=${center.lat}&longitude=${center.lon}&radius=${"%.1f".format(miles)}&limit=$limit"
    }

    /** POST target for stations along a route; see [routeForm]. */
    val nearbyRouteUrl get() = "$BASE/nearby-route.json?api_key=${enc(apiKey)}"

    /** Form body for [nearbyRouteUrl]. [corridorMeters] is the distance either side of the route. */
    fun routeForm(route: List<LatLon>, corridorMeters: Double): Map<String, String> {
        val pts = Geo.thin(route, 2_000.0).joinToString(",") { "${it.lon} ${it.lat}" }
        return mapOf(
            "route" to "LINESTRING($pts)",
            "distance" to "%.1f".format(corridorMeters / 1609.344),
            "fuel_type" to "ELEC",
            "status" to "E,T,P",
            "access" to "public",
            "limit" to "all",
        )
    }

    override fun parse(json: String): List<ChargingStation> =
        JSON.decodeFromString<Response>(json).stations.map { it.toStation() }

    @Serializable
    private data class Response(@SerialName("fuel_stations") val stations: List<Station> = emptyList())

    @Serializable
    private data class Station(
        val id: Long,
        @SerialName("station_name") val name: String = "",
        @SerialName("street_address") val street: String? = null,
        val city: String? = null,
        val state: String? = null,
        val zip: String? = null,
        val latitude: Double,
        val longitude: Double,
        @SerialName("ev_network") val network: String? = null,
        @SerialName("status_code") val statusCode: String? = null,
        @SerialName("ev_level1_evse_num") val l1: Int? = null,
        @SerialName("ev_level2_evse_num") val l2: Int? = null,
        @SerialName("ev_dc_fast_num") val dc: Int? = null,
        @SerialName("ev_connector_types") val connectorTypes: List<String>? = null,
        @SerialName("access_days_time") val hours: String? = null,
        @SerialName("ev_pricing") val pricing: String? = null,
        @SerialName("date_last_confirmed") val lastConfirmed: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    ) {
        fun toStation(): ChargingStation {
            val speed = when {
                (dc ?: 0) > 0 -> ChargerSpeed.DC_FAST
                (l2 ?: 0) > 0 -> ChargerSpeed.LEVEL2
                (l1 ?: 0) > 0 -> ChargerSpeed.LEVEL1
                else -> null
            }
            return ChargingStation(
                id = "nrel:$id",
                source = "NREL AFDC",
                name = name,
                address = listOfNotNull(street, city, listOfNotNull(state, zip).joinToString(" ").ifBlank { null })
                    .joinToString(", "),
                location = LatLon(latitude, longitude),
                network = network,
                status = when (statusCode) {
                    "E" -> ChargerStatus.OPERATIONAL
                    "T" -> ChargerStatus.OUT_OF_SERVICE
                    "P" -> ChargerStatus.PLANNED
                    else -> ChargerStatus.UNKNOWN
                },
                maxSpeed = speed,
                // NREL does not give per-station kW in this record; leave null rather than guess.
                maxPowerKw = null,
                level1Ports = l1 ?: 0,
                level2Ports = l2 ?: 0,
                dcFastPorts = dc ?: 0,
                connectors = connectorTypes.orEmpty().map { Connector(connectorLabel(it), null, 0) },
                accessHours = hours,
                pricing = pricing,
                lastUpdated = lastConfirmed ?: updatedAt,
            )
        }
    }

    companion object {
        private const val BASE = "https://developer.nrel.gov/api/alt-fuel-stations/v1"
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun connectorLabel(code: String) = when (code) {
            "J1772" -> "J1772"
            "J1772COMBO" -> "CCS1"
            "CHADEMO" -> "CHAdeMO"
            "TESLA" -> "NACS (Tesla)"
            "J3400" -> "NACS (J3400)"
            "NEMA1450", "NEMA515", "NEMA520" -> "Wall outlet ($code)"
            else -> code
        }
    }
}
