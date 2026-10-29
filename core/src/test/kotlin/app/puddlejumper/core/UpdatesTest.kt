package app.puddlejumper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private fun release(tag: String, apk: String? = "puddlejumper-$tag.apk", prerelease: Boolean = false) = """
        {"tag_name":"$tag","name":"Puddlejumper $tag","draft":false,"prerelease":$prerelease,"assets":[
          ${apk?.let { """{"name":"$it","browser_download_url":"https://example.org/$it","size":123}""" } ?: ""}
        ]}"""

    private fun list(vararg r: String) = r.joinToString(",", "[", "]")

    @Test fun picksNewestAboveInstalled() {
        val json = list(release("v0.6.5"), release("v0.6.10"), release("v0.6.6"), release("latest", "puddlejumper-debug.apk", true))
        val up = Updates.newest(json, "0.6.5")!!
        assertEquals("0.6.10", up.version)
        assertEquals("https://example.org/puddlejumper-v0.6.10.apk", up.apkUrl)
        assertEquals(123, up.sizeBytes)
        assertEquals("Puddlejumper v0.6.10", up.title)
    }

    @Test fun noneWhenUpToDate() {
        assertNull(Updates.newest(list(release("v0.6.5"), release("v0.6.4")), "0.6.5"))
        assertNull(Updates.newest(list(release("v0.7")), "0.7.0"))
    }

    @Test fun skipsPrereleasesAndReleasesWithoutApk() {
        val json = list(release("v0.9", prerelease = true), release("v0.8", apk = null), release("v0.7"))
        assertEquals("0.7", Updates.newest(json, "0.6.5")!!.version)
    }

    @Test fun comparesVersionsNumerically() {
        val v = { s: String -> Updates.parseVersion(s)!! }
        assertTrue(Updates.compare(v("0.6.10"), v("0.6.9")) > 0)
        assertTrue(Updates.compare(v("v1.0"), v("0.99.99")) > 0)
        assertEquals(0, Updates.compare(v("0.6"), v("0.6.0")))
        assertNull(Updates.parseVersion("latest"))
    }
}
