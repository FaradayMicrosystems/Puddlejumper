package app.puddlejumper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer versioned release on GitHub with its APK. */
data class AppUpdate(val version: String, val title: String, val apkUrl: String, val sizeBytes: Long)

/**
 * Finds the newest versioned release ("vX.Y.Z" with puddlejumper-vX.Y.Z.apk) above the installed version.
 * The "latest" pre-release of main is ignored: it has no version number to compare.
 */
object Updates {
    fun releasesUrl(repo: String) = "https://api.github.com/repos/$repo/releases?per_page=30"

    fun newest(json: String, installed: String): AppUpdate? {
        val current = parseVersion(installed) ?: return null
        return JSON.decodeFromString<List<Release>>(json)
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { r ->
                val v = parseVersion(r.tag) ?: return@mapNotNull null
                val apk = r.assets.firstOrNull { it.name == "puddlejumper-${r.tag}.apk" } ?: return@mapNotNull null
                v to AppUpdate(r.tag.removePrefix("v"), r.name.ifBlank { r.tag }, apk.url, apk.size)
            }
            .filter { compare(it.first, current) > 0 }
            .maxWithOrNull { a, b -> compare(a.first, b.first) }
            ?.second
    }

    /** "v0.6.5" or "0.6.5" to [0, 6, 5]; null if not a version number. */
    fun parseVersion(s: String): List<Int>? {
        val parts = s.trim().removePrefix("v").split('.')
        return parts.map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
    }

    /** Compares numerically part by part; missing parts count as 0 (0.6 == 0.6.0). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val name: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    private val JSON = Json { ignoreUnknownKeys = true }
}
