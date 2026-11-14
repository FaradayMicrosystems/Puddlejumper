package app.puddlejumper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.puddlejumper.core.ChargerSource
import app.puddlejumper.core.ChargingStation
import app.puddlejumper.core.DistanceUnit
import app.puddlejumper.core.Forecast
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.LegReport
import app.puddlejumper.core.MapboxRouter
import app.puddlejumper.core.NavFix
import app.puddlejumper.core.NavProgress
import app.puddlejumper.core.NavigationEngine
import app.puddlejumper.core.NominatimGeocoder
import app.puddlejumper.core.NrelSource
import app.puddlejumper.core.OpenMeteo
import app.puddlejumper.core.OpenChargeMapSource
import app.puddlejumper.core.OsrmRouter
import app.puddlejumper.core.PhotonGeocoder
import app.puddlejumper.core.Place
import app.puddlejumper.core.RangePlanner
import app.puddlejumper.core.RangeSettings
import app.puddlejumper.core.RerouteGate
import app.puddlejumper.core.Route
import app.puddlejumper.core.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Waypoint(val name: String, val location: LatLon, val isCurrentLocation: Boolean = false)

/** Turn-by-turn guidance in progress. */
data class NavUi(
    /** The route being driven; replaced after each reroute. */
    val route: Route,
    /** Latest position on the route; null until the first GPS reading. */
    val progress: NavProgress? = null,
    val rerouting: Boolean = false,
    val muted: Boolean = false,
    /** Number of the stop the driver is heading to, as shown on the map (stop 1 is the start). */
    val nextStopNumber: Int = 2,
)

data class UiState(
    val waypoints: List<Waypoint> = emptyList(),
    val route: Route? = null,
    val legs: List<LegReport> = emptyList(),
    val chargers: List<ChargingStation> = emptyList(),
    val settings: RangeSettings,
    val unit: DistanceUnit,
    /** Draw extra rings at 2 and 3 full charges around each stop. */
    val multiJumpView: Boolean = false,
    /** True once the user has tapped Chargers; the map then reloads chargers as it moves. */
    val chargersOn: Boolean = false,
    /** Weather layer: forecasts at the map centre and at every stop. */
    val weatherOn: Boolean = false,
    /** Forecast at the map centre, once loaded. */
    val centerForecast: Forecast? = null,
    /** Forecasts at stops, keyed by the stop's position. */
    val stopForecasts: Map<LatLon, Forecast> = emptyMap(),
    val loading: Boolean = false,
    /** Non-null while navigating. */
    val nav: NavUi? = null,
    /** One-shot message for a snackbar; cleared by [MainViewModel.messageShown]. */
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    /** Mapbox when this build has a token (repository secret MAPBOX_ACCESS_TOKEN), else the OSRM test server. */
    private val router: Router =
        BuildConfig.MAPBOX_ACCESS_TOKEN.takeIf { it.isNotBlank() }?.let { MapboxRouter(it) } ?: OsrmRouter()
    val routerName: String get() = router.name

    private val _state = MutableStateFlow(
        UiState(
            settings = RangeSettings(prefs.fullRangeMeters, prefs.reservePercent, prefs.chargeAtEachStop),
            unit = prefs.unit,
            multiJumpView = prefs.multiJumpView,
            weatherOn = prefs.weatherOn,
        )
    )
    val state: StateFlow<UiState> = _state

    fun addWaypoint(name: String, location: LatLon) = _state.update {
        // Route no longer matches the stops; keep the old line hidden until the user re-routes.
        it.copy(waypoints = it.waypoints + Waypoint(name, location), route = null, legs = emptyList())
    }

    /** Adds a stop from a tap-and-hold on the map, then names it by its street address in the background. */
    fun dropPin(location: LatLon) {
        val label = "Pin %.4f, %.4f".format(location.lat, location.lon)
        addWaypoint(label, location)
        viewModelScope.launch {
            val name = runCatching { NominatimGeocoder.parseReverse(Http.get(NominatimGeocoder.reverseUrl(location))) }
                .getOrNull() ?: return@launch
            _state.update { s ->
                s.copy(waypoints = s.waypoints.map { if (it.location == location && it.name == label) it.copy(name = name) else it })
            }
        }
    }

    fun removeWaypoint(index: Int) = _state.update {
        it.copy(waypoints = it.waypoints.filterIndexed { i, _ -> i != index }, route = null, legs = emptyList())
    }

    /** Last known GPS position; it becomes stop 1 automatically. */
    private var currentLocation: LatLon? = null

    /** Makes [location] stop 1, unless a current-location stop is already in the list. */
    fun setCurrentLocation(location: LatLon) {
        currentLocation = location
        _state.update {
            if (it.waypoints.any { w -> w.isCurrentLocation }) it
            else it.copy(
                waypoints = listOf(currentLocationStop(location)) + it.waypoints,
                route = null,
                legs = emptyList(),
            )
        }
    }

    private fun currentLocationStop(location: LatLon) = Waypoint("My location", location, isCurrentLocation = true)

    /** Removes all stops except your current location, which stays as stop 1. */
    fun clear() = _state.update {
        it.copy(
            waypoints = listOfNotNull(currentLocation?.let(::currentLocationStop)),
            route = null,
            legs = emptyList(),
            chargers = emptyList(),
            chargersOn = false,
        )
    }

    fun setFullRange(valueInUnit: Double) = updateSettings { it.copy(fullRangeMeters = _state.value.unit.toMeters(valueInUnit)) }

    fun setChargeAtEachStop(on: Boolean) = updateSettings { it.copy(chargeToFullAtEachStop = on) }

    fun setReservePercent(p: Int) = updateSettings { it.copy(reservePercent = p) }

    fun setMultiJumpView(on: Boolean) {
        prefs.multiJumpView = on
        _state.update { it.copy(multiJumpView = on) }
    }

    fun setWeatherOn(on: Boolean) {
        prefs.weatherOn = on
        _state.update { if (on) it.copy(weatherOn = true) else it.copy(weatherOn = false, centerForecast = null, stopForecasts = emptyMap()) }
    }

    /** Map centre for the weather layer; set after every pan or zoom. */
    private var weatherCenter: LatLon? = null

    fun setWeatherCenter(center: LatLon) {
        weatherCenter = center
        if (_state.value.weatherOn) refreshWeather()
    }

    /** Forecasts by rounded position (about 10 km, near the forecast models' grid), with the time they were fetched. */
    private val forecastCache = HashMap<String, Pair<Long, Forecast>>()
    private var weatherJob: Job? = null

    private fun cacheKey(p: LatLon) = "%.1f,%.1f".format(java.util.Locale.ROOT, p.lat, p.lon)

    /** Loads forecasts for the map centre and every stop. Failures are skipped; the layer shows what loaded. */
    private fun refreshWeather() {
        val center = weatherCenter
        val stops = _state.value.waypoints.map { it.location }
        val points = listOfNotNull(center) + stops
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            forecastCache.values.removeAll { now - it.first > FORECAST_MAX_AGE_MS }
            points.filter { cacheKey(it) !in forecastCache }
                .distinctBy(::cacheKey)
                .map { p -> async { p to runCatching { OpenMeteo.parse(Http.get(OpenMeteo.forecastUrl(p)), p) }.getOrNull() } }
                .awaitAll()
                .forEach { (p, f) -> if (f != null) forecastCache[cacheKey(p)] = now to f }
            if (!_state.value.weatherOn) return@launch
            fun at(p: LatLon) = forecastCache[cacheKey(p)]?.second?.copy(location = p)
            _state.update {
                it.copy(
                    centerForecast = center?.let(::at),
                    stopForecasts = stops.mapNotNull { p -> at(p)?.let { f -> p to f } }.toMap(),
                )
            }
        }
    }

    // Declared after the weather fields it uses, so they exist when it first runs.
    init {
        // Stops added, removed or moved: fetch weather for them too.
        viewModelScope.launch {
            _state.map { it.weatherOn to it.waypoints.map { w -> w.location } }
                .distinctUntilChanged()
                .collect { (on, _) -> if (on) refreshWeather() }
        }
    }

    fun setUnit(unit: DistanceUnit) {
        prefs.unit = unit
        _state.update { it.copy(unit = unit) }
    }

    private fun updateSettings(change: (RangeSettings) -> RangeSettings) {
        val s = change(_state.value.settings)
        prefs.fullRangeMeters = s.fullRangeMeters
        prefs.reservePercent = s.reservePercent
        prefs.chargeAtEachStop = s.chargeToFullAtEachStop
        _state.update { it.copy(settings = s, legs = it.route?.let { r -> plan(r, s) }.orEmpty()) }
    }

    private fun plan(route: Route, s: RangeSettings) = RangePlanner.plan(route.legs.map { it.distanceMeters }, s)

    fun computeRoute() {
        val stops = _state.value.waypoints
        if (stops.size < 2) return say("Add at least two stops first.")
        launchLoading {
            val route = router.parse(Http.get(router.routeUrl(stops.map { it.location })))
            _state.update { it.copy(route = route, legs = plan(route, it.settings)) }
        }
    }

    // ---- Turn-by-turn navigation ----

    private val _speech = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Prompts to say out loud; the screen speaks them unless muted. */
    val speech: SharedFlow<String> = _speech

    private var engine: NavigationEngine? = null
    /** Stops after the start, in order: leg i of the original route ends at navStops[i]. */
    private var navStops: List<LatLon> = emptyList()
    /** Legs finished on earlier routes of this trip (each reroute starts a new route from where you are). */
    private var legOffset = 0
    private val rerouteGate = RerouteGate()
    private var rerouteJob: Job? = null

    fun startNavigation() {
        val s = _state.value
        val route = s.route ?: return say("Plan a route first.")
        engine = NavigationEngine(route)
        navStops = s.waypoints.drop(1).map { it.location }
        legOffset = 0
        _state.update { it.copy(nav = NavUi(route = route, muted = prefs.voiceMuted)) }
        if (router is OsrmRouter) say("Using the OSRM test server: this build has no Mapbox token.")
    }

    fun stopNavigation() {
        rerouteJob?.cancel()
        rerouteGate.done()
        engine = null
        navStops = emptyList()
        _state.update { it.copy(nav = null) }
    }

    fun setVoiceMuted(muted: Boolean) {
        prefs.voiceMuted = muted
        _state.update { s -> s.nav?.let { s.copy(nav = it.copy(muted = muted)) } ?: s }
    }

    /** A GPS reading while navigating. */
    fun onNavFix(fix: NavFix) {
        val e = engine ?: return
        val p = e.update(fix)
        p.speak?.let { _speech.tryEmit(it) }
        _state.update { s ->
            val nav = s.nav ?: return@update s
            s.copy(nav = nav.copy(progress = p, nextStopNumber = legOffset + p.legIndex + 2))
        }
        if (p.offRoute && rerouteGate.tryStart(fix.timeMs)) reroute(fix, p)
    }

    /** Fetches a new route from where you are to the stops you have not reached yet. */
    private fun reroute(fix: NavFix, p: NavProgress) {
        val remaining = navStops.drop(legOffset + p.legIndex)
        if (remaining.isEmpty()) return rerouteGate.done()
        _state.update { s -> s.nav?.let { s.copy(nav = it.copy(rerouting = true)) } ?: s }
        _speech.tryEmit("Rerouting.")
        rerouteJob = viewModelScope.launch {
            try {
                // Moving direction helps the router avoid starting with a U-turn; GPS bearing is noise when nearly stopped.
                val heading = fix.bearingDeg?.takeIf { (fix.speedMps ?: 0.0) > 3.0 }
                val route = router.parse(Http.get(router.routeUrl(listOf(fix.location) + remaining, heading)))
                if (engine == null) return@launch
                legOffset += p.legIndex
                engine = NavigationEngine(route)
                _state.update { s -> s.nav?.let { s.copy(nav = it.copy(route = route, progress = null, rerouting = false)) } ?: s }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.nav?.let { s.copy(nav = it.copy(rerouting = false)) } ?: s }
                say("Couldn't get a new route: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                rerouteGate.done()
            }
        }
    }

    /** Loads chargers along the route when there is one (NREL), otherwise around [center]. */
    private fun chargerSource(): ChargerSource? = when (prefs.chargerSource) {
        ChargerSourceType.NREL -> NrelSource(prefs.nrelKey)
        ChargerSourceType.OPEN_CHARGE_MAP -> prefs.ocmKey.takeIf { it.isNotBlank() }?.let { OpenChargeMapSource(it) }
    }

    private var refreshJob: Job? = null

    /**
     * Quietly loads chargers for the visible map after a pan or zoom, adding them to those already shown.
     * Does nothing until the user has turned chargers on with the Chargers button.
     */
    fun refreshChargersForView(center: LatLon, radiusMeters: Double) {
        if (!_state.value.chargersOn) return
        val source = chargerSource() ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val found = try {
                source.parse(Http.get(source.nearbyUrl(center, radiusMeters, limit = MAX_PER_REQUEST)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // a failed background refresh keeps what is already on the map
            }
            _state.update { s ->
                val merged = (found + s.chargers).distinctBy { it.id }
                // Keep the newest results when the map has collected a lot of markers.
                s.copy(chargers = merged.take(MAX_SHOWN))
            }
        }
    }

    fun loadChargers(center: LatLon, radiusMeters: Double) {
        val source = chargerSource() ?: return say("This build has no Open Charge Map key.")
        val route = _state.value.route
        _state.update { it.copy(chargersOn = true) }
        launchLoading {
            val stations = if (source is NrelSource && route != null) {
                source.parse(Http.postForm(source.nearbyRouteUrl, source.routeForm(route.geometry, CORRIDOR_M)))
            } else {
                source.parse(Http.get(source.nearbyUrl(center, radiusMeters, limit = MAX_PER_REQUEST)))
            }
            _state.update { it.copy(chargers = stations) }
            val where = if (route != null && source is NrelSource) " along your route" else ""
            val demo = if (source is NrelSource && prefs.nrelKey == "DEMO_KEY") " (shared demo key)" else ""
            say("${stations.size} chargers from ${source.name}$where$demo")
        }
    }

    fun search(query: String, onResult: (List<Place>) -> Unit) {
        if (query.isBlank()) return
        launchLoading {
            val places = NominatimGeocoder.parse(Http.get(NominatimGeocoder.searchUrl(query)))
            if (places.isEmpty()) say("No places found for \"$query\"") else onResult(places)
        }
    }

    /** Search-as-you-type suggestions, nearest to [near] first. Throws on network errors; callers ignore them. */
    suspend fun suggest(query: String, near: LatLon?): List<Place> =
        PhotonGeocoder.parse(Http.get(PhotonGeocoder.autocompleteUrl(query, near)))

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    private fun launchLoading(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: e.javaClass.simpleName)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        /** Chargers within 8 km of the route line. */
        const val CORRIDOR_M = 8_000.0
        const val MAX_PER_REQUEST = 500
        const val MAX_SHOWN = 1_500
        const val FORECAST_MAX_AGE_MS = 60 * 60 * 1000L
    }
}
