package app.puddlejumper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks navigation prompts: the built-in Scottish voice ([NeuralVoice]) unless Settings picks the phone's own
 * voice, or the Scottish voice can't load. Music and podcasts are turned down while a prompt plays.
 */
class NavVoice(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var ready = false
    private val pending = mutableListOf<String>()
    private var nextId = 0
    private var playing = 0

    var muted = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onReady(status) }

    private fun onReady(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        // Prompts are written in English with metric units.
        val lang = if (Locale.getDefault().language == "en") Locale.getDefault() else Locale.UK
        if (tts.setLanguage(lang) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.UK)
        tts.setAudioAttributes(attributes)
        ready = true
        val queued = pending.toList()
        pending.clear()
        queued.forEach(::say)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
        })
    }

    @Synchronized
    private fun finished() {
        playing = (playing - 1).coerceAtLeast(0)
        if (playing == 0) audio.abandonAudioFocusRequest(focus)
    }

    init {
        if (prefs.navVoice != Prefs.PHONE_VOICE) NeuralVoice.warmUp(appContext)
    }

    fun say(text: String) {
        if (muted) return
        val voiceId = prefs.navVoice
        if (voiceId != Prefs.PHONE_VOICE) {
            synchronized(this) {
                if (playing == 0) audio.requestAudioFocus(focus)
                playing++
            }
            if (NeuralVoice.speak(appContext, text, voiceId, attributes, onDone = ::finished)) return
            finished() // model unavailable: fall through to the phone's voice
        }
        if (!ready) { pending += text; return }
        synchronized(this) {
            if (playing == 0) audio.requestAudioFocus(focus)
            playing++
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "pj-${nextId++}")
    }

    fun stop() {
        pending.clear()
        NeuralVoice.stop()
        tts.stop()
        synchronized(this) { playing = 0 }
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
