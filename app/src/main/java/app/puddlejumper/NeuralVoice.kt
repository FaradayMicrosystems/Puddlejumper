package app.puddlejumper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/** A Scottish male speaker in the built-in voice model (VCTK corpus, CC BY 4.0). */
data class ScottishVoice(
    /** VCTK speaker id, stored in settings. */
    val id: String,
    /** Index of the speaker inside the model. */
    val sid: Int,
    val label: String,
)

/**
 * Offline neural voice (Piper VCTK model run by sherpa-onnx). Works with no network. One engine per
 * process, shared by navigation and the settings preview. Everything runs on one background thread,
 * so prompts play one after another.
 */
object NeuralVoice {
    /** The six lightest Scottish male voices in the model, highest pitch first. */
    val voices = listOf(
        ScottishVoice("p255", 84, "Galloway (lightest)"),
        ScottishVoice("p247", 5, "Argyll"),
        ScottishVoice("p285", 70, "Edinburgh A"),
        ScottishVoice("p272", 62, "Edinburgh B"),
        ScottishVoice("p252", 79, "Edinburgh C"),
        ScottishVoice("p241", 98, "Perth"),
    )
    const val DEFAULT_ID = "p255"

    /** Credit required by the voice data's CC BY 4.0 licence. */
    const val CREDIT = "Voice: CSTR VCTK Corpus, University of Edinburgh (CC BY 4.0), via Piper and sherpa-onnx."

    private const val TAG = "NeuralVoice"
    private const val ASSET_DIR = "voice"
    /** Bump when the bundled espeak data changes, so the copy on the phone is refreshed. */
    private const val DATA_VERSION = "vctk-medium-1"

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var tts: OfflineTts? = null
    @Volatile var failed = false
        private set
    /** Bumped by [stop]; queued prompts from an older generation are skipped. */
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    fun voice(id: String) = voices.firstOrNull { it.id == id } ?: voices.first { it.id == DEFAULT_ID }

    /** Starts loading the model in the background (takes a few seconds the first time). */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        worker.execute { load(app) }
    }

    /**
     * Speaks [text] with speaker [voiceId]. [onStart] and [onDone] run on the voice thread around playback.
     * Returns false at once if the model could not be loaded, so the caller can fall back to the phone's voice.
     */
    fun speak(
        context: Context,
        text: String,
        voiceId: String,
        attributes: AudioAttributes,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
    ): Boolean {
        if (failed) return false
        val app = context.applicationContext
        val gen = generation
        worker.execute {
            if (gen != generation) return@execute
            val engine = load(app) ?: return@execute
            try {
                val audio = engine.generate(text, voice(voiceId).sid, 1.0f)
                if (gen != generation || audio.samples.isEmpty()) return@execute
                onStart()
                try {
                    play(audio.samples, audio.sampleRate, attributes, gen)
                } finally {
                    onDone()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not speak: $text", e)
            }
        }
        return true
    }

    /** Stops the prompt playing now and drops any waiting. */
    fun stop() {
        generation++
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun load(context: Context): OfflineTts? {
        tts?.let { return it }
        if (failed) return null
        return try {
            val dataDir = copyEspeakData(context)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$ASSET_DIR/model.onnx",
                        tokens = "$ASSET_DIR/tokens.txt",
                        dataDir = dataDir.absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            OfflineTts(context.assets, config).also { tts = it }
        } catch (e: Throwable) {
            // Missing model, unsupported CPU or native library problem: use the phone's own voice instead.
            Log.e(TAG, "Voice model failed to load", e)
            failed = true
            null
        }
    }

    /** The phoneme data must be a real folder, not an asset; copy it once per data version. */
    private fun copyEspeakData(context: Context): File {
        val root = File(context.filesDir, "voice")
        val dest = File(root, "espeak-ng-data")
        val marker = File(root, "version")
        if (marker.exists() && marker.readText() == DATA_VERSION && dest.isDirectory) return dest
        root.deleteRecursively()
        copyAssetDir(context, "$ASSET_DIR/espeak-ng-data", dest)
        marker.writeText(DATA_VERSION)
        return dest
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(dest, child))
    }

    private fun play(samples: FloatArray, sampleRate: Int, attributes: AudioAttributes, gen: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        track = t
        try {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            // Wait until it has played (or was stopped), with a safety limit.
            val limitMs = samples.size * 1000L / sampleRate + 2_000
            val start = System.currentTimeMillis()
            while (gen == generation && t.playbackHeadPosition < samples.size &&
                System.currentTimeMillis() - start < limitMs
            ) Thread.sleep(40)
            t.stop()
        } finally {
            track = null
            t.release()
        }
    }
}
