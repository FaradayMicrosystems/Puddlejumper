package app.puddlejumper.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Photon (photon.komoot.io): OpenStreetMap search built for search-as-you-type.
 * Used for suggestions because Nominatim's usage policy forbids autocomplete.
 */
object PhotonGeocoder {
    fun autocompleteUrl(query: String, near: LatLon? = null, limit: Int = 6): String =
        "https://photon.komoot.io/api/?limit=$limit&q=" + URLEncoder.encode(query, "UTF-8") +
            (near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: "")

    fun parse(json: String): List<Place> =
        JSON.decodeFromString<Response>(json).features.mapNotNull { f ->
            val c = f.geometry.coordinates
            if (c.size < 2) return@mapNotNull null
            val label = f.properties.label() ?: return@mapNotNull null
            Place(label, LatLon(c[1], c[0]))
        }.distinctBy { it.name }

    @Serializable
    private data class Response(val features: List<Feature> = emptyList())

    @Serializable
    private data class Feature(val geometry: Geometry, val properties: Properties = Properties())

    @Serializable
    private data class Geometry(val coordinates: List<Double> = emptyList())

    @Serializable
    private data class Properties(
        val name: String? = null,
        val housenumber: String? = null,
        val street: String? = null,
        val city: String? = null,
        val state: String? = null,
        val country: String? = null,
    ) {
        /** "Name, Street 12, City, State, Country" with blanks and repeats left out. */
        fun label(): String? {
            val street = listOfNotNull(street, housenumber).joinToString(" ").ifBlank { null }
            return listOfNotNull(name, street, city, state, country)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .ifBlank { null }
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}
