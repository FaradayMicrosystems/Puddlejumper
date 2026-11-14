package app.puddlejumper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    // OSM tile, Nominatim and OSRM usage policies all ask for an identifying User-Agent.
    private const val USER_AGENT = "Puddlejumper/${BuildConfig.VERSION_NAME} (Android prototype)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    suspend fun get(url: String): String = execute(Request.Builder().url(url).build())

    suspend fun postForm(url: String, form: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).post(body).build())
    }

    /** Saves [url] to [out], reporting progress 0..1 when the size is known. Redirects are followed. */
    suspend fun download(url: String, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} from ${request.url.host}")
            val body = res.body ?: throw IOException("Empty download from ${request.url.host}")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} from ${request.url.host}: ${text.take(200)}")
            text
        }
    }
}
