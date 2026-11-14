package app.puddlejumper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.puddlejumper.core.ChargerSource
import app.puddlejumper.core.ChargingStation
import app.puddlejumper.core.Forecast
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.LegReport
import app.puddlejumper.core.MapboxRouter
import app.puddlejumper.core.NavProgress
import app.puddlejumper.core.NominatimGeocoder
import app.puddlejumper.core.NrelSource
import app.puddlejumper.core.OpenMeteo
import app.puddlejumper.core.OpenChargeMapSource
import app.puddlejumper.core.OsrmRouter
import app.puddlejumper.core.PhotonGeocoder
import app.puddlejumper.core.Place
import app.puddlejumper.core.RangePlanner
import app.puddlejumper.core.RangeSettings
import app.puddlejumper.core.BranchRouter
import app.puddlejumper.core.EvModel
import app.puddlejumper.core.Geo
import app.puddlejumper.core.OpenMeteoElevation
import app.puddlejumper.core.Path
import app.puddlejumper.core.RangeTree
import app.puddlejumper.core.TreeBranch
import app.puddlejumper.core.Route
import app.puddlejumper.core.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
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
    /** Direction of travel from GPS, once moving; used to turn the map heading-up. */
    val headingDeg: Double? = null,
    /** Latest GPS position (not snapped to the route). */
    val position: LatLon? = null,
)

/** The yellow range tree: roads you can reach from [origin] on the battery you have now. */
data class RangeTreeUi(
    val origin: LatLon,
    val branches: List<TreeBranch>,
    /** True once heights are in; until then the tree assumes flat roads. */
    val terrain: Boolean,
    /** True while routes are still loading. */
    val building: Boolean,
) {
    /** Longest road distance in the tree. */
    val maxRoadMeters: Double get() = branches.maxOfOrNull { it.roadMeters } ?: 0.0
}

data class UiState(
    val waypoints: List<Waypoint> = emptyList(),
    val route: Route? = null,
    val legs: List<LegReport> = emptyList(),
    val chargers: List<ChargingStation> = emptyList(),
    val settings: RangeSettings,
    /** The car: battery size, consumption, weight. */
    val ev: EvModel,
    /** Battery charge now, 0..100. */
    val batteryPercent: Double,
    /** Draw the range tree from your location. */
    val rangeTreeOn: Boolean = true,
    /** Null until there is a GPS fix and the first routes are in. */
    val tree: RangeTreeUi? = null,
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
        EvModel(prefs.usableKwh, prefs.kwhPer100Km, prefs.massKg).let { ev ->
            UiState(
                settings = rangeSettings(ev, prefs.batteryPercent, prefs.reservePercent, prefs.chargeAtEachStop),
                ev = ev,
                batteryPercent = prefs.batteryPercent,
                rangeTreeOn = prefs.rangeTreeOn,
                // Stops from the last session. GPS adds "My location" in front once it has a fix.
                waypoints = prefs.savedStops,
                weatherOn = prefs.weatherOn,
            )
        }
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

    fun setBatteryPercent(p: Double) {
        val v = p.coerceIn(0.0, 100.0)
        prefs.batteryPercent = v
        _state.update { it.copy(batteryPercent = v) }
        updateSettings { it }
    }

    /** Car details from Settings. Invalid (zero or negative) values are ignored. */
    fun setEv(usableKwh: Double, kwhPer100Km: Double, massKg: Double) {
        val ev = runCatching { EvModel(usableKwh, kwhPer100Km, massKg) }.getOrElse { return say("Battery, consumption and weight must be above 0.") }
        prefs.usableKwh = usableKwh
        prefs.kwhPer100Km = kwhPer100Km
        prefs.massKg = massKg
        _state.update { it.copy(ev = ev) }
        updateSettings { it }
    }

    fun setChargeAtEachStop(on: Boolean) = updateSettings { it.copy(chargeToFullAtEachStop = on) }

    fun setReservePercent(p: Int) = updateSettings { it.copy(reservePercent = p) }

    fun setRangeTreeOn(on: Boolean) {
        prefs.rangeTreeOn = on
        _state.update { it.copy(rangeTreeOn = on, tree = if (on) it.tree else null) }
    }

    // ---- Range tree ----

    /** Latest GPS position, from the map's location marker or from navigation. */
    private val liveLocation = MutableStateFlow<LatLon?>(null)
    /** Where the battery countdown last measured from. */
    private var lastDrainAt: LatLon? = null

    /**
     * A GPS reading. Moves the range tree with you and, while driving, lowers the battery estimate by
     * distance x consumption (flat; the driver can correct it with the slider at any time).
     */
    fun onLocation(p: LatLon, speedMps: Double?) {
        liveLocation.value = p
        val last = lastDrainAt
        val driving = (speedMps ?: 0.0) >= DRIVING_SPEED_MPS
        if (last == null || !driving || !prefs.countDownBattery) {
            lastDrainAt = p
            return
        }
        val d = Geo.distanceMeters(last, p)
        if (d < DRAIN_STEP_M) return
        lastDrainAt = p
        if (d > DRAIN_MAX_JUMP_M) return // GPS jump or app was asleep: don't guess
        val s = _state.value
        setBatteryPercent(s.batteryPercent - s.ev.percentOf(s.ev.flatKwh(d)))
    }

    private val branchRouter: BranchRouter =
        BuildConfig.MAPBOX_ACCESS_TOKEN.takeIf { it.isNotBlank() }?.let { BranchRouter.mapbox(it) } ?: BranchRouter.osrm()

    /** Roads fetched for the tree, before cutting to the battery. */
    private class RawTree(val origin: LatLon, val reachMeters: Double, val paths: Map<Double, Path>)

    private var raw: RawTree? = null
    private var fetchJob: Job? = null
    private var lastFetchMs = 0L
    /** Terrain heights by ~1 km grid cell; hills don't move, so this is kept for the whole session. */
    private val heights = object : LinkedHashMap<String, Double>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX_HEIGHTS
    }

    /** Re-cuts the fetched roads for the current battery and car, or fetches new roads when you've moved. */
    private fun updateTree(loc: LatLon) {
        val s = _state.value
        val budget = s.ev.budgetKwh(s.batteryPercent, s.settings.reservePercent)
        // Straight-line reach we need roads for: the flat range, plus room for downhill gains.
        val need = s.ev.fullRangeMeters * (s.batteryPercent - s.settings.reservePercent).coerceAtLeast(1.0) / 100.0
        val r = raw
        val fresh = r != null && Geo.distanceMeters(r.origin, loc) < TREE_MOVE_M && need <= r.reachMeters * 0.9
        if (r != null) clipTree(r, budget, building = fetchJob?.isActive == true)
        if (fresh || fetchJob?.isActive == true) return
        if (r != null && System.currentTimeMillis() - lastFetchMs < TREE_MIN_INTERVAL_MS) return
        fetchTree(loc, need * TREE_REACH_MARGIN)
    }

    private fun clipTree(r: RawTree, budgetKwh: Double, building: Boolean) {
        val s = _state.value
        var terrain = false
        val branches = r.paths.map { (bearing, path) ->
            val samples = RangeTree.elevationSamples(path, HEIGHT_SPACING_M, path.lengthMeters)
            val profile = samples.mapNotNull { (d, p) -> heights[OpenMeteoElevation.key(p)]?.let { d to it } }
            if (profile.size >= samples.size / 2) terrain = true
            RangeTree.clip(bearing, path, if (profile.size >= 2) profile else emptyList(), s.ev, budgetKwh)
        }
        _state.update { it.copy(tree = RangeTreeUi(r.origin, branches, terrain, building)) }
    }

    /** One road per bearing (spaced to respect the router's limits), then terrain heights for them. */
    private fun fetchTree(origin: LatLon, reachMeters: Double) {
        lastFetchMs = System.currentTimeMillis()
        val progressive = raw == null
        if (!progressive) _state.update { it.copy(tree = it.tree?.copy(building = true)) }
        fetchJob = viewModelScope.launch {
            val paths = linkedMapOf<Double, Path>()
            var building = RawTree(origin, reachMeters, paths)
            RangeTree.targets(origin, reachMeters).zip(RangeTree.BEARINGS).forEach { (target, bearing) ->
                val path = try {
                    branchRouter.parse(Http.get(branchRouter.url(origin, target)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // skip this bearing; the rest of the tree still shows
                }
                if (path != null && path.points.size >= 2) {
                    paths[bearing] = path
                    building = RawTree(origin, reachMeters, LinkedHashMap(paths))
                    // First tree: draw branches as they arrive. Later trees replace the old one when done.
                    if (progressive) {
                        raw = building
                        redrawTree(building, true)
                    }
                }
                delay(branchRouter.requestGapMs)
            }
            if (paths.isEmpty()) {
                _state.update { it.copy(tree = it.tree?.copy(building = false)) }
                return@launch say("Couldn't load roads for the range tree. It will retry as you move.")
            }
            fetchHeights(paths.values)
            raw = building
            redrawTree(building, false)
        }
    }

    private fun redrawTree(r: RawTree, building: Boolean) {
        val s = _state.value
        if (!s.rangeTreeOn) return
        clipTree(r, s.ev.budgetKwh(s.batteryPercent, s.settings.reservePercent), building)
    }

    /** Looks up heights the cache doesn't have yet, 100 per request. Failures leave those roads flat. */
    private suspend fun fetchHeights(paths: Collection<Path>) {
        val missing = paths.asSequence()
            .flatMap { RangeTree.elevationSamples(it, HEIGHT_SPACING_M, it.lengthMeters).asSequence() }
            .map { it.second }
            .filter { OpenMeteoElevation.key(it) !in heights }
            .distinctBy { OpenMeteoElevation.key(it) }
            .take(MAX_HEIGHT_LOOKUPS)
            .toList()
        for (chunk in missing.chunked(OpenMeteoElevation.MAX_POINTS)) {
            val values = try {
                OpenMeteoElevation.parse(Http.get(OpenMeteoElevation.url(chunk)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            chunk.zip(values).forEach { (p, h) -> if (h != null) heights[OpenMeteoElevation.key(p)] = h }
        }
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

    // Declared after the weather and tree fields it uses, so they exist when it first runs.
    init {
        // Range tree: follow your position and redraw when the battery or car changes. The short wait
        // lets the battery slider settle; cutting fetched roads is instant, only a move fetches new ones.
        viewModelScope.launch {
            combine(
                liveLocation,
                _state.map { Triple(it.rangeTreeOn, it.ev, it.batteryPercent to it.settings.reservePercent) }.distinctUntilChanged(),
            ) { loc, (on, _, _) -> if (on) loc else null }
                .conflate()
                .collectLatest { loc ->
                    if (loc == null) return@collectLatest
                    delay(TREE_DEBOUNCE_MS)
                    updateTree(loc)
                }
        }
        // Navigation GPS keeps the tree and battery estimate moving while the map screen is off.
        viewModelScope.launch {
            NavSession.state.map { it?.position to it?.headingDeg }.distinctUntilChanged().collect { (p, heading) ->
                if (p != null) onLocation(p, if (heading != null) DRIVING_SPEED_MPS else 0.0)
            }
        }
        // Save stops whenever they change, so they are still there after the app is closed.
        viewModelScope.launch {
            _state.map { it.waypoints }
                .distinctUntilChanged()
                .collect { prefs.savedStops = it }
        }
        // Stops added, removed or moved: fetch weather for them too.
        viewModelScope.launch {
            _state.map { it.weatherOn to it.waypoints.map { w -> w.location } }
                .distinctUntilChanged()
                .collect { (on, _) -> if (on) refreshWeather() }
        }
    }

    private fun updateSettings(change: (RangeSettings) -> RangeSettings) {
        val cur = _state.value
        val s = change(cur.settings).let { rangeSettings(cur.ev, cur.batteryPercent, it.reservePercent, it.chargeToFullAtEachStop) }
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

    // ---- Turn-by-turn navigation (runs in NavSession + NavigationService so it survives the screen) ----

    init {
        viewModelScope.launch { NavSession.state.collect { n -> _state.update { it.copy(nav = n) } } }
        viewModelScope.launch { NavSession.messages.collect { say(it) } }
    }

    fun startNavigation() {
        val s = _state.value
        val route = s.route ?: return say("Plan a route first.")
        NavSession.start(getApplication(), route, s.waypoints.drop(1).map { it.location }, router, prefs.voiceMuted)
        if (router is OsrmRouter) say("Using the OSRM test server: this build has no Mapbox token.")
    }

    fun stopNavigation() = NavSession.stop(getApplication())

    fun setVoiceMuted(muted: Boolean) {
        prefs.voiceMuted = muted
        NavSession.setMuted(muted)
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
        const val TREE_DEBOUNCE_MS = 400L
        /** Fetch new roads once you are this far from where the tree was built. */
        const val TREE_MOVE_M = 2_000.0
        /** At most one road fetch per this long (24 requests each), except the first. */
        const val TREE_MIN_INTERVAL_MS = 45_000L
        /** Route targets this far past the flat range, so descents and long roads still get cut by the battery. */
        const val TREE_REACH_MARGIN = 1.15
        const val HEIGHT_SPACING_M = 3_000.0
        const val MAX_HEIGHT_LOOKUPS = 3_000
        const val MAX_HEIGHTS = 50_000
        /** Below this GPS speed (about 15 km/h) the battery estimate doesn't count down (walking, parked). */
        const val DRIVING_SPEED_MPS = 4.0
        const val DRAIN_STEP_M = 200.0
        const val DRAIN_MAX_JUMP_M = 3_000.0

        fun rangeSettings(ev: EvModel, batteryPercent: Double, reservePercent: Int, chargeEach: Boolean) = RangeSettings(
            fullRangeMeters = ev.fullRangeMeters,
            reservePercent = reservePercent,
            chargeToFullAtEachStop = chargeEach,
            startRangeMeters = ev.fullRangeMeters * batteryPercent / 100.0,
        )
    }
}
