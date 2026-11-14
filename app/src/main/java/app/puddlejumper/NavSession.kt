package app.puddlejumper

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.NavFix
import app.puddlejumper.core.NavProgress
import app.puddlejumper.core.NavigationEngine
import app.puddlejumper.core.RerouteGate
import app.puddlejumper.core.Route
import app.puddlejumper.core.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The trip being navigated. Lives for the whole app process, not one screen, so guidance continues
 * while the screen is off or the app is in the background ([NavigationService] feeds it GPS and speaks).
 * All calls are made on the main thread.
 */
object NavSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<NavUi?>(null)
    /** Non-null while navigating. */
    val state: StateFlow<NavUi?> = _state

    private val _speech = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Prompts to say out loud. */
    val speech: SharedFlow<String> = _speech

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short notices for the screen (e.g. a failed reroute). */
    val messages: SharedFlow<String> = _messages

    private var router: Router? = null
    private var engine: NavigationEngine? = null
    /** Stops after the start, in order: leg i of the first route ends at stops[i]. */
    private var stops: List<LatLon> = emptyList()
    /** Legs finished on earlier routes of this trip (each reroute starts a new route from where you are). */
    private var legOffset = 0
    private val rerouteGate = RerouteGate()
    private var rerouteJob: Job? = null

    fun start(context: Context, route: Route, stopsAfterStart: List<LatLon>, router: Router, muted: Boolean) {
        rerouteJob?.cancel()
        rerouteGate.done()
        this.router = router
        engine = NavigationEngine(route)
        stops = stopsAfterStart
        legOffset = 0
        _state.value = NavUi(route = route, muted = muted)
        ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
    }

    fun stop(context: Context) {
        rerouteJob?.cancel()
        rerouteGate.done()
        engine = null
        stops = emptyList()
        _state.value = null
        context.stopService(Intent(context, NavigationService::class.java))
    }

    fun setMuted(muted: Boolean) = _state.update { it?.copy(muted = muted) }

    /** A GPS reading while navigating. */
    fun onFix(fix: NavFix) {
        val e = engine ?: return
        val p = e.update(fix)
        p.speak?.let { _speech.tryEmit(it) }
        val moving = fix.bearingDeg != null && (fix.speedMps ?: 0.0) > 2.0
        _state.update {
            it?.copy(
                progress = p,
                nextStopNumber = legOffset + p.legIndex + 2,
                headingDeg = if (moving) fix.bearingDeg else it.headingDeg,
                position = fix.location,
            )
        }
        if (p.offRoute && rerouteGate.tryStart(fix.timeMs)) reroute(fix, p)
    }

    /** Fetches a new route from where you are to the stops you have not reached yet. */
    private fun reroute(fix: NavFix, p: NavProgress) {
        val r = router
        val remaining = stops.drop(legOffset + p.legIndex)
        if (r == null || remaining.isEmpty()) return rerouteGate.done()
        _state.update { it?.copy(rerouting = true) }
        _speech.tryEmit("Rerouting.")
        rerouteJob = scope.launch {
            try {
                // Moving direction helps the router avoid starting with a U-turn; GPS bearing is noise when nearly stopped.
                val heading = fix.bearingDeg?.takeIf { (fix.speedMps ?: 0.0) > 3.0 }
                val route = r.parse(Http.get(r.routeUrl(listOf(fix.location) + remaining, heading)))
                if (engine == null) return@launch
                legOffset += p.legIndex
                engine = NavigationEngine(route)
                _state.update { it?.copy(route = route, progress = null, rerouting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it?.copy(rerouting = false) }
                _messages.tryEmit("Couldn't get a new route: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                rerouteGate.done()
            }
        }
    }
}
