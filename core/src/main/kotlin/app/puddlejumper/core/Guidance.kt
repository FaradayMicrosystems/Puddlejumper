package app.puddlejumper.core

import java.util.Locale
import kotlin.math.roundToInt

/** Spoken prompts and banners for routers that do not send their own (OSRM). Metric only. */
object Guidance {

    /** Distances before a manoeuvre at which a prompt is spoken, longest first. */
    private val PROMPT_DISTANCES_M = listOf(2_000.0, 500.0)

    /** The last prompt, said just before the manoeuvre. */
    const val FINAL_PROMPT_M = 60.0

    /**
     * Adds prompts and banners to every step that has none. Step i's prompts announce step i+1's
     * manoeuvre, because that is where step i ends.
     */
    fun withPrompts(steps: List<RouteStep>): List<RouteStep> = steps.mapIndexed { i, step ->
        val next = steps.getOrNull(i + 1) ?: return@mapIndexed step
        val length = step.distanceMeters
        val voice = step.voice.ifEmpty {
            buildList {
                // The opening prompt at the start of the step.
                if (i == 0) add(VoiceInstruction(length, "${step.instruction}."))
                else if (length > 3_000) add(VoiceInstruction(length, "Continue for ${spokenDistance(length)}."))
                for (d in PROMPT_DISTANCES_M) {
                    if (d < length * 0.9) add(VoiceInstruction(d, "In ${spokenDistance(d)}, ${lowerFirst(next.instruction)}."))
                }
                add(VoiceInstruction(minOf(FINAL_PROMPT_M, length), "${next.instruction}."))
            }.distinctBy { it.distanceBeforeEndM }
        }
        val banners = step.banners.ifEmpty { listOf(BannerInstruction(length, next.instruction, next.maneuver)) }
        step.copy(voice = voice, banners = banners)
    }

    /** "450 metres", "2 kilometres", "1.5 kilometres". */
    fun spokenDistance(meters: Double): String = when {
        meters < 1_000 -> {
            val m = if (meters < 100) (meters / 10).roundToInt() * 10 else (meters / 50).roundToInt() * 50
            if (m >= 1_000) "1 kilometre" else "$m metres"
        }
        else -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else "%.1f".format(Locale.ROOT, km)
            if (text == "1") "1 kilometre" else "$text kilometres"
        }
    }

    /** Short on-screen distance: "80 m", "450 m", "2.4 km", "35 km". */
    fun shortDistance(meters: Double): String = when {
        meters < 100 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 1_000 -> "${(meters / 50).roundToInt() * 50} m"
        meters < 10_000 -> "%.1f km".format(Locale.ROOT, meters / 1_000)
        else -> "${(meters / 1_000).roundToInt()} km"
    }

    /** An arrow for the turn banner. Plain Unicode so no image assets are needed. */
    fun arrow(m: Maneuver): String = when {
        m.type == "arrive" -> "\u2691" // flag
        m.type == "roundabout" || m.type == "rotary" || m.type == "roundabout turn" -> "\u21BB"
        m.modifier == "uturn" -> "\u21B6"
        m.modifier == "sharp right" -> "\u21B1"
        m.modifier == "right" -> "\u2192"
        m.modifier == "slight right" -> "\u2197"
        m.modifier == "sharp left" -> "\u21B0"
        m.modifier == "left" -> "\u2190"
        m.modifier == "slight left" -> "\u2196"
        else -> "\u2191"
    }

    /** "14 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(0)
        return if (min < 60) "$min min" else "%d h %02d min".format(Locale.ROOT, min / 60, min % 60)
    }

    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase(Locale.ROOT) }
}
