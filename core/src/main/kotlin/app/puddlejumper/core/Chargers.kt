package app.puddlejumper.core

enum class ChargerStatus(val label: String) {
    /** Live: a plug is free right now (only live-data providers such as TomTom or PlugShare report this). */
    AVAILABLE("Available now"),
    /** Live: all plugs in use. */
    OCCUPIED("In use"),
    /** Station is open and working, but no live plug availability is known. */
    OPERATIONAL("Operational"),
    /** Down, under maintenance, or temporarily closed. */
    OUT_OF_SERVICE("Down / maintenance"),
    PLANNED("Planned (not open yet)"),
    UNKNOWN("Status unknown"),
}

enum class ChargerSpeed(val label: String) {
    LEVEL1("Level 1"),
    LEVEL2("Level 2"),
    DC_FAST("DC fast"),
}

data class Connector(val type: String, val powerKw: Double?, val count: Int)

data class ChargingStation(
    val id: String,
    val source: String,
    val name: String,
    val address: String,
    val location: LatLon,
    val network: String?,
    val status: ChargerStatus,
    val maxSpeed: ChargerSpeed?,
    val maxPowerKw: Double?,
    val level1Ports: Int,
    val level2Ports: Int,
    val dcFastPorts: Int,
    val connectors: List<Connector>,
    val accessHours: String?,
    val pricing: String?,
    /** When the source last confirmed or updated the record (ISO date string as given by the source). */
    val lastUpdated: String?,
)

/** Builds request URLs and parses responses. HTTP itself lives in the app so this stays pure Kotlin. */
interface ChargerSource {
    val name: String
    fun nearbyUrl(center: LatLon, radiusMeters: Double, limit: Int = 200): String
    fun parse(json: String): List<ChargingStation>
}
