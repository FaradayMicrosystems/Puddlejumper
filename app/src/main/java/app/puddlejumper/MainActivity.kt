package app.puddlejumper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.puddlejumper.core.ChargerStatus
import app.puddlejumper.core.ChargingStation
import app.puddlejumper.core.Forecast
import app.puddlejumper.core.Geo
import app.puddlejumper.core.Guidance
import app.puddlejumper.core.Km
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.Place
import app.puddlejumper.core.Route
import app.puddlejumper.core.WeatherFormat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var map: MapView
    private lateinit var rangeLabel: TextView
    private lateinit var rangeSlider: Slider
    private lateinit var chargeEachStop: MaterialSwitch
    private lateinit var rangeTree: MaterialCheckBox
    private lateinit var weatherLayer: MaterialCheckBox
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private var locationOverlay: MyLocationNewOverlay? = null

    /** Overlays this screen draws from state; replaced on every render. */
    private val drawn = mutableListOf<Overlay>()
    private var zoomedRoute: Route? = null
    private var suggestJob: Job? = null
    private var followOnGrant = true
    private lateinit var updater: Updater

    /** True while this screen shows navigation (map zoomed in and following the car). */
    private var navScreen = false

    /** Shows whatever suggestions were fetched last; the server already matched them, so no local filtering. */
    private inner class SuggestionAdapter :
        ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_dropdown_item_1line) {
        var places: List<Place> = emptyList()
            private set

        fun update(newPlaces: List<Place>) {
            places = newPlaces
            clear()
            addAll(newPlaces.map { it.name })
            notifyDataSetChanged()
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { count = places.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }
    }

    /** Tap and hold anywhere on the map, including inside range areas, to drop a stop. */
    private val mapEvents = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?) = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            p ?: return false
            map.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            vm.dropPin(LatLon(p.latitude, p.longitude))
            Snackbar.make(map, "Stop ${vm.state.value.waypoints.size} added. Tap it to remove.", Snackbar.LENGTH_SHORT).show()
            return true
        }
    })

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.any { it }) startLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        rangeLabel = findViewById(R.id.rangeLabel)
        rangeSlider = findViewById(R.id.rangeSlider)
        chargeEachStop = findViewById(R.id.chargeEachStop)
        rangeTree = findViewById(R.id.rangeTree)
        weatherLayer = findViewById(R.id.weatherLayer)
        summary = findViewById(R.id.summary)
        progress = findViewById(R.id.progress)

        setUpMap()
        setUpControls()
        vm.setWeatherCenter(viewCenter())
        // Ask for location right away so your position becomes stop 1 without any taps.
        onMyLocation(follow = false)
        updater = Updater(this, map)
        if (savedInstanceState == null) updater.checkInBackground()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    private fun setUpMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(39.5, -98.35))
        map.overlays.add(mapEvents)
        // Reload chargers and weather for the new view once the map has stopped moving for a moment.
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = refreshLayers()
            override fun onZoom(event: ZoomEvent?): Boolean = refreshLayers()
        }, 700))
    }

    private fun setUpControls() {
        val search = findViewById<AutoCompleteTextView>(R.id.search)
        val suggestions = SuggestionAdapter()
        search.setAdapter(suggestions)
        search.setOnItemClickListener { _, _, position, _ ->
            val p = suggestions.places.getOrNull(position) ?: return@setOnItemClickListener
            search.setText("")
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
            vm.addWaypoint(p.name.substringBefore(","), p.location)
            map.controller.animateTo(GeoPoint(p.location.lat, p.location.lon))
        }
        search.doAfterTextChanged { text ->
            suggestJob?.cancel()
            val q = text?.toString()?.trim().orEmpty()
            if (q.length < 3) {
                suggestions.update(emptyList())
                return@doAfterTextChanged
            }
            suggestJob = lifecycleScope.launch {
                delay(350) // wait for a pause in typing
                val c = map.mapCenter
                val places = runCatching { vm.suggest(q, LatLon(c.latitude, c.longitude)) }.getOrNull()
                    ?: return@launch
                suggestions.update(places)
                if (search.hasFocus() && places.isNotEmpty()) search.showDropDown()
            }
        }
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            vm.search(v.text.toString()) { places -> pickPlace(places) }
            true
        }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<FloatingActionButton>(R.id.myLocation).setOnClickListener { onMyLocation() }

        rangeSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.setBatteryPercent(value.toDouble()) }
        chargeEachStop.setOnCheckedChangeListener { _, on -> vm.setChargeAtEachStop(on) }
        rangeTree.setOnCheckedChangeListener { _, on -> vm.setRangeTreeOn(on) }
        weatherLayer.setOnCheckedChangeListener { _, on ->
            vm.setWeatherCenter(viewCenter())
            vm.setWeatherOn(on)
        }

        // Route plans the trip; once planned, the same button starts turn-by-turn guidance.
        findViewById<Button>(R.id.routeButton).setOnClickListener {
            if (vm.state.value.route != null) startNavigation() else vm.computeRoute()
        }
        findViewById<Button>(R.id.navEnd).setOnClickListener { vm.stopNavigation() }
        findViewById<ImageButton>(R.id.navMute).setOnClickListener {
            val muted = vm.state.value.nav?.muted ?: return@setOnClickListener
            vm.setVoiceMuted(!muted)
        }
        findViewById<Button>(R.id.chargersButton).setOnClickListener {
            vm.loadChargers(viewCenter(), viewRadius())
        }
        findViewById<Button>(R.id.stepsButton).setOnClickListener { showSteps() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { vm.clear() }
    }

    private fun render(s: UiState) {
        progress.isVisible = s.loading

        // Battery controls: the slider is the charge now; the label says how far that gets you.
        val snapped = s.batteryPercent.roundToInt().toFloat().coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo)
        if (rangeSlider.value != snapped) rangeSlider.value = snapped
        rangeLabel.text = batteryText(s)
        if (chargeEachStop.isChecked != s.settings.chargeToFullAtEachStop) {
            chargeEachStop.isChecked = s.settings.chargeToFullAtEachStop
        }
        if (rangeTree.isChecked != s.rangeTreeOn) rangeTree.isChecked = s.rangeTreeOn
        if (weatherLayer.isChecked != s.weatherOn) weatherLayer.isChecked = s.weatherOn
        summary.text = summaryText(s)
        findViewById<Button>(R.id.routeButton).text = getString(if (s.route != null) R.string.start else R.string.route)
        renderNav(s)

        drawOverlays(s)
        if (s.route != null && s.route !== zoomedRoute) {
            zoomedRoute = s.route
            zoomToStops(s.route.geometry)
        }

        s.message?.let {
            Snackbar.make(map, it, Snackbar.LENGTH_LONG).show()
            vm.messageShown()
        }
    }

    private fun batteryText(s: UiState): String {
        val flat = s.ev.fullRangeMeters * (s.batteryPercent - s.settings.reservePercent).coerceAtLeast(0.0) / 100.0
        val head = "Battery ${s.batteryPercent.roundToInt()}% · about ${Km.format(flat)} to ${s.settings.reservePercent}%"
        val tree = s.tree
        val note = when {
            !s.rangeTreeOn -> ""
            tree == null -> "\nRange tree: waiting for your location"
            tree.building -> "\nRange tree: loading roads…"
            !tree.terrain -> "\nRange tree: flat estimate (terrain not loaded)"
            else -> "\nRange tree: up to ${Km.format(tree.maxRoadMeters)} by road, terrain included"
        }
        return head + note
    }

    private fun summaryText(s: UiState): String {
        if (s.waypoints.isEmpty()) return getString(R.string.hint_long_press)
        val route = s.route ?: return "${s.waypoints.size} stop(s). Tap Route to plan."
        val hrs = route.durationSeconds / 3600
        val lines = mutableListOf(
            "Total ${Km.format(route.distanceMeters)}, about %.1f h driving".format(hrs)
        )
        s.legs.forEach { leg ->
            val flag = if (leg.reachable) "" else "  ⚠ below ${s.settings.reservePercent}% reserve"
            lines += "Stop ${leg.legIndex + 1}→${leg.legIndex + 2}: ${Km.format(leg.distanceMeters)}, " +
                "arrive with ${Km.format(leg.arriveRangeMeters.coerceAtLeast(0.0))}$flag"
        }
        return lines.joinToString("\n")
    }

    private fun drawOverlays(s: UiState) {
        map.overlays.removeAll(drawn)
        drawn.clear()

        // Arrival range for stop i (i >= 1) comes from leg i-1.
        val arriveAt = s.legs.associate { it.legIndex + 1 to it.arriveRangeMeters }

        // Range tree: yellow roads from your position as far as the battery goes (dark edge so it shows on pale maps).
        s.tree?.takeIf { s.rangeTreeOn }?.branches?.forEach { b ->
            if (b.points.size < 2) return@forEach
            val pts = b.points.map { GeoPoint(it.lat, it.lon) }
            drawn += treeLine(pts, Color.argb(170, 120, 90, 0), 11f)
            drawn += treeLine(pts, Color.rgb(255, 214, 0), 6f)
        }

        // While navigating, draw the route being driven (it changes after a reroute).
        (s.nav?.route ?: s.route)?.let { route ->
            drawn += Polyline(map).apply {
                setPoints(route.geometry.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Color.rgb(43, 108, 176)
                outlinePaint.strokeWidth = if (s.nav != null) 16f else 10f
                setOnClickListener { _, _, _ -> false }
            }
        }

        s.chargers.forEach { st ->
            drawn += Marker(map).apply {
                position = GeoPoint(st.location.lat, st.location.lon)
                icon = Icons.charger(resources, st)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = st.name
                setOnMarkerClickListener { _, _ -> showCharger(st); true }
            }
        }

        // Weather: 3-day badge above each stop and at the map centre; tap for 5 days.
        s.waypoints.forEachIndexed { i, wp ->
            s.stopForecasts[wp.location]?.let { weatherMarker(it, "Stop ${i + 1}: ${wp.name}", above = true)?.let { m -> drawn += m } }
        }
        s.centerForecast?.let { weatherMarker(it, "Map centre", above = false)?.let { m -> drawn += m } }

        s.waypoints.forEachIndexed { i, wp ->
            drawn += Marker(map).apply {
                position = GeoPoint(wp.location.lat, wp.location.lon)
                icon = Icons.waypoint(resources, i + 1)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = wp.name
                setOnMarkerClickListener { _, _ -> showWaypoint(i, wp, arriveAt[i]); true }
            }
        }

        map.overlays.addAll(drawn)
        // Keep the tap-and-hold handler on top so no range area or marker swallows the gesture.
        // Its single-tap handler returns false, so taps still reach markers underneath.
        map.overlays.remove(mapEvents)
        map.overlays.add(mapEvents)
        map.invalidate()
    }

    private fun weatherMarker(f: Forecast, title: String, above: Boolean): Marker? {
        if (f.days.isEmpty()) return null
        return Marker(map).apply {
            position = GeoPoint(f.location.lat, f.location.lon)
            icon = Icons.pill(resources, WeatherFormat.badge(f.days))
            // Above a stop it sits clear of the stop's numbered dot.
            setAnchor(Marker.ANCHOR_CENTER, if (above) 2.2f else Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> showForecast(title, f); true }
        }
    }

    private fun showForecast(title: String, f: Forecast) {
        MaterialAlertDialogBuilder(this)
            .setTitle("5-day weather: $title")
            .setMessage(f.days.joinToString("\n\n") { WeatherFormat.detail(it) } + "\n\nForecast: Open-Meteo")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun treeLine(points: List<GeoPoint>, color: Int, width: Float) = Polyline(map).apply {
        setPoints(points)
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        infoWindow = null
        // Let taps fall through to the map so long-press still adds stops on the tree.
        setOnClickListener { _, _, _ -> false }
    }

    private fun showCharger(st: ChargingStation) {
        val lines = buildList {
            if (st.address.isNotBlank()) add(st.address)
            st.network?.let { add("Network: $it") }
            add("Status: ${st.status.label}")
            if (st.status == ChargerStatus.OPERATIONAL) add("(${st.source} does not report live plug availability)")
            st.maxSpeed?.let { add("Speed: ${it.label}" + (st.maxPowerKw?.let { kw -> ", up to %.0f kW".format(kw) } ?: "")) }
            add("Ports: ${st.dcFastPorts} DC fast, ${st.level2Ports} Level 2, ${st.level1Ports} Level 1")
            if (st.connectors.isNotEmpty()) {
                add("Connectors: " + st.connectors.joinToString { c ->
                    c.type + (c.powerKw?.let { " %.0f kW".format(it) } ?: "") + (if (c.count > 1) " ×${c.count}" else "")
                })
            }
            st.accessHours?.let { add("Hours: $it") }
            st.pricing?.let { add("Pricing: $it") }
            st.lastUpdated?.let { add("Last confirmed: $it") }
            vm.state.value.waypoints.lastOrNull()?.let {
                add("Straight-line from last stop: ${Km.format(Geo.distanceMeters(it.location, st.location))}")
            }
            add("Source: ${st.source}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(st.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Add as stop") { _, _ -> vm.addWaypoint(st.name, st.location) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showWaypoint(index: Int, wp: Waypoint, arriveRangeM: Double?) {
        val msg = buildString {
            append("Full-charge range: ${Km.format(vm.state.value.settings.fullRangeMeters)} (flat roads)")
            arriveRangeM?.let { append("\nRange left on arrival: ${Km.format(it.coerceAtLeast(0.0))}") }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop ${index + 1}: ${wp.name}")
            .setMessage(msg)
            .setPositiveButton("Remove stop") { _, _ -> vm.removeWaypoint(index) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSteps() {
        val s = vm.state.value
        val route = s.route ?: return Snackbar.make(map, "Plan a route first.", Snackbar.LENGTH_SHORT).show()
        val items = route.legs.flatMapIndexed { i, leg ->
            listOf("— Stop ${i + 1} to ${i + 2} —") +
                leg.steps.map { st -> "${st.instruction}  (${Km.format(st.distanceMeters)})" }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Directions")
            .setItems(items.toTypedArray(), null)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun pickPlace(places: List<Place>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add stop")
            .setItems(places.map { it.name }.toTypedArray()) { _, which ->
                val p = places[which]
                vm.addWaypoint(p.name.substringBefore(","), p.location)
                map.controller.animateTo(GeoPoint(p.location.lat, p.location.lon))
            }
            .show()
    }

    private fun voiceLabel(id: String) =
        if (id == Prefs.PHONE_VOICE) "Phone's own voice" else "Scottish: ${NeuralVoice.voice(id).label}"

    /** Choose the navigation voice; each tap plays a sample. Saved straight away. */
    private fun pickVoice(onPicked: (String) -> Unit) {
        val ids = NeuralVoice.voices.map { it.id } + Prefs.PHONE_VOICE
        val labels = ids.map(::voiceLabel).toTypedArray()
        val preview = previewVoice ?: NavVoice(this).also { previewVoice = it }
        MaterialAlertDialogBuilder(this)
            .setTitle("Navigation voice (tap to hear)")
            .setSingleChoiceItems(labels, ids.indexOf(vm.prefs.navVoice).coerceAtLeast(0)) { _, which ->
                vm.prefs.navVoice = ids[which]
                onPicked(ids[which])
                preview.stop()
                preview.say("In 500 metres, turn left onto Princes Street.")
            }
            .setPositiveButton("Done", null)
            .show()
    }

    /** Plays voice samples in Settings; made on first use. */
    private var previewVoice: NavVoice? = null

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val prefs = vm.prefs
        val state = vm.state.value
        view.findViewById<RadioButton>(
            if (prefs.chargerSource == ChargerSourceType.NREL) R.id.sourceNrel else R.id.sourceOcm
        ).isChecked = true
        val reserveLabel = view.findViewById<TextView>(R.id.reserveLabel)
        val reserve = view.findViewById<Slider>(R.id.reserveSlider)
        reserve.value = (state.settings.reservePercent / 5 * 5).toFloat().coerceIn(0f, 50f)
        reserveLabel.text = "Arrival reserve: ${reserve.value.toInt()}%"
        reserve.addOnChangeListener { _, v, _ -> reserveLabel.text = "Arrival reserve: ${v.toInt()}%" }
        val usable = view.findViewById<EditText>(R.id.usableKwh)
        val consumption = view.findViewById<EditText>(R.id.consumption)
        val mass = view.findViewById<EditText>(R.id.massKg)
        val countDown = view.findViewById<MaterialSwitch>(R.id.countDownBattery)
        usable.setText(trim(state.ev.usableKwh))
        consumption.setText(trim(state.ev.kwhPer100Km))
        mass.setText(trim(state.ev.massKg))
        countDown.isChecked = prefs.countDownBattery
        view.findViewById<TextView>(R.id.versionLabel).text = "Installed: v${BuildConfig.VERSION_NAME}"
        val voiceButton = view.findViewById<Button>(R.id.navVoice)
        voiceButton.text = voiceLabel(prefs.navVoice)
        voiceButton.setOnClickListener { pickVoice { voiceButton.text = voiceLabel(it) } }
        view.findViewById<TextView>(R.id.voiceCredit).text = NeuralVoice.CREDIT

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                vm.setReservePercent(reserve.value.toInt())
                vm.setEv(
                    usable.text.toString().toDoubleOrNull() ?: state.ev.usableKwh,
                    consumption.text.toString().toDoubleOrNull() ?: state.ev.kwhPer100Km,
                    mass.text.toString().toDoubleOrNull() ?: state.ev.massKg,
                )
                prefs.countDownBattery = countDown.isChecked
                prefs.chargerSource =
                    if (view.findViewById<RadioButton>(R.id.sourceOcm).isChecked) ChargerSourceType.OPEN_CHARGE_MAP
                    else ChargerSourceType.NREL
            }
            .setNegativeButton("Cancel", null)
            .show()
        view.findViewById<Button>(R.id.checkUpdates).setOnClickListener {
            dialog.dismiss()
            updater.checkNow()
        }
    }

    /** 75.0 -> "75", 17.5 -> "17.5". */
    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun onMyLocation(follow: Boolean = true) {
        followOnGrant = follow
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocation(follow)
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    /**
     * Shows your position and makes it stop 1. [follow] keeps the map centred on you (the location button);
     * at launch the map centres once and then stays where you pan it.
     */
    private fun startLocation(follow: Boolean = followOnGrant) {
        val overlay = locationOverlay ?: object : MyLocationNewOverlay(GpsMyLocationProvider(this), map) {
            // Every GPS reading moves the range tree and counts the battery down while driving.
            override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                super.onLocationChanged(location, source)
                location ?: return
                val speed = if (location.hasSpeed()) location.speed.toDouble() else null
                runOnUiThread { vm.onLocation(LatLon(location.latitude, location.longitude), speed) }
            }
        }.also {
            locationOverlay = it
            map.overlays.add(it)
        }
        overlay.enableMyLocation()
        if (follow) {
            overlay.enableFollowLocation()
            map.controller.setZoom(12.0)
        }
        overlay.runOnFirstFix {
            val fix = overlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                vm.setCurrentLocation(LatLon(fix.latitude, fix.longitude))
                if (!follow) {
                    map.controller.setZoom(11.0)
                    map.controller.animateTo(fix)
                }
            }
        }
    }

    private fun refreshLayers(): Boolean {
        vm.refreshChargersForView(viewCenter(), viewRadius())
        vm.setWeatherCenter(viewCenter())
        return false
    }

    private fun viewCenter() = map.mapCenter.let { LatLon(it.latitude, it.longitude) }

    /** Half the visible diagonal, capped so a zoomed-out map doesn't request a whole continent. */
    private fun viewRadius(): Double {
        val box = map.boundingBox
        return (Geo.distanceMeters(LatLon(box.latNorth, box.lonWest), LatLon(box.latSouth, box.lonEast)) / 2)
            .coerceIn(2_000.0, 250_000.0)
    }

    /** Fits the map to a newly planned route. */
    private fun zoomToStops(points: List<LatLon>) {
        if (points.size < 2) return
        val b = Geo.bounds(points)
        map.zoomToBoundingBox(BoundingBox(b.north, b.east, b.south, b.west), true, 120)
    }

    // ---- Turn-by-turn navigation ----

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startNavigation() {
        if (!hasLocationPermission()) {
            Snackbar.make(map, "Turn-by-turn needs precise location. Allow it, then tap Start again.", Snackbar.LENGTH_LONG).show()
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        // Android 13+: the next-turn notification needs permission. Guidance works without it.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.startNavigation()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Shows or hides the navigation screen. GPS and voice run in [NavigationService]. */
    private fun renderNav(s: UiState) {
        val nav = s.nav
        val navigating = nav != null
        findViewById<View>(R.id.navBanner).isVisible = navigating
        findViewById<View>(R.id.navBar).isVisible = navigating
        findViewById<View>(R.id.searchCard).isVisible = !navigating
        findViewById<View>(R.id.planCard).isVisible = !navigating
        findViewById<View>(R.id.myLocation).isVisible = !navigating

        if (nav == null) {
            if (navScreen) leaveNavScreen()
            return
        }
        if (!navScreen) enterNavScreen()
        // Follow the car, heading up once it is moving.
        nav.position?.let { pos ->
            val at = nav.progress?.takeIf { !it.offRoute }?.snapped ?: pos
            map.controller.animateTo(GeoPoint(at.lat, at.lon))
        }
        nav.headingDeg?.let { map.mapOrientation = -it.toFloat() }

        findViewById<ImageButton>(R.id.navMute).setImageResource(
            if (nav.muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        )
        val arrow = findViewById<TextView>(R.id.navArrow)
        val distance = findViewById<TextView>(R.id.navDistance)
        val instruction = findViewById<TextView>(R.id.navInstruction)
        val secondary = findViewById<TextView>(R.id.navSecondary)
        val trip = findViewById<TextView>(R.id.navTrip)
        val p = nav.progress
        when {
            nav.rerouting -> {
                arrow.text = "\u21BB"
                distance.text = "Rerouting…"
                instruction.text = "Finding a new route from here"
                secondary.isVisible = false
            }
            p == null -> {
                arrow.text = "\u2191"
                distance.text = "Waiting for GPS…"
                instruction.text = nav.route.legs.firstOrNull()?.steps?.firstOrNull()?.instruction.orEmpty()
                secondary.isVisible = false
            }
            p.arrived -> {
                arrow.text = "\u2691"
                distance.text = "Arrived"
                instruction.text = s.waypoints.lastOrNull()?.name.orEmpty()
                secondary.isVisible = false
            }
            else -> {
                val b = p.banner
                arrow.text = b?.let { Guidance.arrow(it.maneuver) } ?: "\u2191"
                distance.text = Guidance.shortDistance(p.distanceToManeuverM)
                instruction.text = b?.text ?: ""
                secondary.text = b?.secondary.orEmpty()
                secondary.isVisible = !b?.secondary.isNullOrBlank()
            }
        }
        trip.text = if (p == null || p.arrived) {
            if (p?.arrived == true) "Trip finished. Tap End." else "Routes by ${vm.routerName}"
        } else {
            val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (p.durationRemainingS * 1000).toLong()))
            val toStop = if (p.distanceToNextStopM < p.distanceRemainingM - 50) {
                "\nStop ${nav.nextStopNumber} in ${Guidance.shortDistance(p.distanceToNextStopM)}"
            } else ""
            "${Guidance.shortDistance(p.distanceRemainingM)} · ${Guidance.duration(p.durationRemainingS)} · arrive $eta$toStop"
        }
    }

    private fun enterNavScreen() {
        navScreen = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationOverlay?.disableFollowLocation()
        map.controller.setZoom(17.0)
    }

    private fun leaveNavScreen() {
        navScreen = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        map.mapOrientation = 0f
        map.controller.setZoom(13.0)
    }

    override fun onDestroy() {
        previewVoice?.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        locationOverlay?.disableFollowLocation()
        super.onPause()
    }
}
