package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Open Charge Map (worldwide, community data). Free key from openchargemap.org.
 * Docs: https://openchargemap.org/site/develop/api
 */
class OpenChargeMapSource(private val apiKey: String) : ChargerSource {
    override val name = "Open Charge Map"

    override fun nearbyUrl(center: LatLon, radiusMeters: Double, limit: Int): String =
        "https://api.openchargemap.io/v3/poi?output=json&compact=false&verbose=false" +
            "&latitude=${center.lat}&longitude=${center.lon}" +
            "&distance=${"%.1f".format(radiusMeters / 1000.0)}&distanceunit=km&maxresults=$limit" +
            "&key=${URLEncoder.encode(apiKey, "UTF-8")}"

    override fun parse(json: String): List<ChargingStation> =
        JSON.decodeFromString<List<Poi>>(json).map { it.toStation() }

    @Serializable
    private data class Poi(
        @SerialName("ID") val id: Long,
        @SerialName("AddressInfo") val address: Address,
        @SerialName("OperatorInfo") val operator: Named? = null,
        @SerialName("StatusType") val status: Status? = null,
        @SerialName("Connections") val connections: List<Connection>? = null,
        @SerialName("UsageCost") val usageCost: String? = null,
        @SerialName("DateLastStatusUpdate") val lastStatusUpdate: String? = null,
    ) {
        fun toStation(): ChargingStation {
            val conns = connections.orEmpty()
            fun ports(level: Int) = conns.filter { it.levelId == level }.sumOf { it.quantity ?: 1 }
            val maxKw = conns.mapNotNull { it.powerKw }.maxOrNull()
            val speed = when {
                ports(3) > 0 || (maxKw ?: 0.0) >= 40 -> ChargerSpeed.DC_FAST
                ports(2) > 0 || (maxKw ?: 0.0) >= 3 -> ChargerSpeed.LEVEL2
                ports(1) > 0 -> ChargerSpeed.LEVEL1
                else -> null
            }
            return ChargingStation(
                id = "ocm:$id",
                source = "Open Charge Map",
                name = address.title ?: "Charging station",
                address = listOfNotNull(address.line1, address.town, address.state, address.postcode)
                    .filter { it.isNotBlank() }.joinToString(", "),
                location = LatLon(address.latitude, address.longitude),
                network = operator?.title,
                status = mapStatus(status),
                maxSpeed = speed,
                maxPowerKw = maxKw,
                level1Ports = ports(1),
                level2Ports = ports(2),
                dcFastPorts = ports(3),
                connectors = conns.map {
                    Connector(it.type?.title ?: "Unknown", it.powerKw, it.quantity ?: 1)
                },
                accessHours = null,
                pricing = usageCost,
                lastUpdated = lastStatusUpdate,
            )
        }
    }

    @Serializable
    private data class Address(
        @SerialName("Title") val title: String? = null,
        @SerialName("AddressLine1") val line1: String? = null,
        @SerialName("Town") val town: String? = null,
        @SerialName("StateOrProvince") val state: String? = null,
        @SerialName("Postcode") val postcode: String? = null,
        @SerialName("Latitude") val latitude: Double,
        @SerialName("Longitude") val longitude: Double,
    )

    @Serializable
    private data class Named(@SerialName("Title") val title: String? = null)

    @Serializable
    private data class Status(
        @SerialName("ID") val id: Int? = null,
        @SerialName("IsOperational") val isOperational: Boolean? = null,
    )

    @Serializable
    private data class Connection(
        @SerialName("ConnectionType") val type: Named? = null,
        @SerialName("PowerKW") val powerKw: Double? = null,
        @SerialName("Quantity") val quantity: Int? = null,
        @SerialName("LevelID") val levelId: Int? = null,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        // OCM status type ids: 10 available, 20 in use, 30 temporarily unavailable, 50 operational,
        // 75 partly operational, 100 not operational, 150 planned, 200 removed, 0 unknown.
        private fun mapStatus(s: Status?): ChargerStatus = when (s?.id) {
            10 -> ChargerStatus.AVAILABLE
            20 -> ChargerStatus.OCCUPIED
            30, 100, 200 -> ChargerStatus.OUT_OF_SERVICE
            150 -> ChargerStatus.PLANNED
            50, 75 -> ChargerStatus.OPERATIONAL
            else -> when (s?.isOperational) {
                true -> ChargerStatus.OPERATIONAL
                false -> ChargerStatus.OUT_OF_SERVICE
                null -> ChargerStatus.UNKNOWN
            }
        }
    }
}
