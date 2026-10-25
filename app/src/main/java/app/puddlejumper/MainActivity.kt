package app.puddlejumper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.puddlejumper.core.ChargerStatus
import app.puddlejumper.core.ChargingStation
import app.puddlejumper.core.DistanceUnit
import app.puddlejumper.core.Geo
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.Place
import app.puddlejumper.core.Route
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var map: MapView
    private lateinit var rangeLabel: TextView
    private lateinit var rangeSlider: Slider
    private lateinit var chargeEachStop: MaterialSwitch
    private lateinit var summary: TextView
    private lateinit var progress: ProgressBar
    private var locationOverlay: MyLocationNewOverlay? = null

    /** Overlays this screen draws from state; replaced on every render. */
    private val drawn = mutableListOf<Overlay>()
    private var sliderUnit: DistanceUnit? = null
    private var zoomedRoute: Route? = null

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
        summary = findViewById(R.id.summary)
        progress = findViewById(R.id.progress)

        setUpMap()
        setUpControls()

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
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                vm.addWaypoint("Dropped pin", LatLon(p.latitude, p.longitude))
                return true
            }
        }))
    }

    private fun setUpControls() {
        val search = findViewById<EditText>(R.id.search)
        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
            vm.search(v.text.toString()) { places -> pickPlace(places) }
            true
        }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<FloatingActionButton>(R.id.myLocation).setOnClickListener { onMyLocation() }

        rangeSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.setFullRange(value.toDouble()) }
        chargeEachStop.setOnCheckedChangeListener { _, on -> vm.setChargeAtEachStop(on) }

        findViewById<Button>(R.id.routeButton).setOnClickListener { vm.computeRoute() }
        findViewById<Button>(R.id.chargersButton).setOnClickListener {
            val c = map.mapCenter
            val box = map.boundingBox
            // Half the visible diagonal, capped so a zoomed-out map doesn't request a whole continent.
            val radius = (Geo.distanceMeters(
                LatLon(box.latNorth, box.lonWest), LatLon(box.latSouth, box.lonEast)
            ) / 2).coerceIn(2_000.0, 80_000.0)
            vm.loadChargers(LatLon(c.latitude, c.longitude), radius)
        }
        findViewById<Button>(R.id.stepsButton).setOnClickListener { showSteps() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { vm.clear() }
    }

    private fun render(s: UiState) {
        progress.isVisible = s.loading

        // Range controls
        if (sliderUnit != s.unit) {
            sliderUnit = s.unit
            rangeSlider.valueFrom = 25f
            rangeSlider.valueTo = if (s.unit == DistanceUnit.MILES) 600f else 1000f
        }
        val v = s.unit.fromMeters(s.settings.fullRangeMeters)
        val snapped = ((v / 5).roundToInt() * 5f).coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo)
        if (rangeSlider.value != snapped) rangeSlider.value = snapped
        rangeLabel.text = "Full-charge range: ${s.unit.format(s.settings.fullRangeMeters)}"
        if (chargeEachStop.isChecked != s.settings.chargeToFullAtEachStop) {
            chargeEachStop.isChecked = s.settings.chargeToFullAtEachStop
        }
        summary.text = summaryText(s)

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

    private fun summaryText(s: UiState): String {
        if (s.waypoints.isEmpty()) return getString(R.string.hint_long_press)
        val route = s.route ?: return "${s.waypoints.size} stop(s). Tap Route to plan."
        val hrs = route.durationSeconds / 3600
        val lines = mutableListOf(
            "Total ${s.unit.format(route.distanceMeters)}, about %.1f h driving".format(hrs)
        )
        s.legs.forEach { leg ->
            val flag = if (leg.reachable) "" else "  ⚠ below ${s.settings.reservePercent}% reserve"
            lines += "Stop ${leg.legIndex + 1}→${leg.legIndex + 2}: ${s.unit.format(leg.distanceMeters)}, " +
                "arrive with ${s.unit.format(leg.arriveRangeMeters.coerceAtLeast(0.0))}$flag"
        }
        return lines.joinToString("\n")
    }

    private fun drawOverlays(s: UiState) {
        map.overlays.removeAll(drawn)
        drawn.clear()

        // Arrival range for stop i (i >= 1) comes from leg i-1.
        val arriveAt = s.legs.associate { it.legIndex + 1 to it.arriveRangeMeters }

        s.waypoints.forEachIndexed { i, wp ->
            // Outer circle: how far you can go from this stop on a full charge.
            drawn += circle(wp.location, s.settings.fullRangeMeters, Color.rgb(27, 127, 90), 0x221B7F5A)
            // Inner circle: range left when you arrive here, before charging.
            arriveAt[i]?.takeIf { it > 0 }?.let {
                drawn += circle(wp.location, it, Color.rgb(230, 130, 0), 0x18E68200)
            }
        }

        s.route?.let { route ->
            drawn += Polyline(map).apply {
                setPoints(route.geometry.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = Color.rgb(43, 108, 176)
                outlinePaint.strokeWidth = 10f
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
        map.invalidate()
    }

    private fun circle(center: LatLon, radiusM: Double, stroke: Int, fill: Int) = Polygon(map).apply {
        points = Geo.circle(center, radiusM).map { GeoPoint(it.lat, it.lon) }
        outlinePaint.color = stroke
        outlinePaint.strokeWidth = 4f
        fillPaint.color = fill
        infoWindow = null
        // Let taps fall through to the map so long-press still adds stops inside a circle.
        setOnClickListener { _, _, _ -> false }
    }

    private fun showCharger(st: ChargingStation) {
        val unit = vm.state.value.unit
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
                add("Straight-line from last stop: ${unit.format(Geo.distanceMeters(it.location, st.location))}")
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
        val unit = vm.state.value.unit
        val msg = buildString {
            append("Full-charge range circle: ${unit.format(vm.state.value.settings.fullRangeMeters)}")
            arriveRangeM?.let { append("\nRange left on arrival: ${unit.format(it.coerceAtLeast(0.0))}") }
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
                leg.steps.map { st -> "${st.instruction}  (${s.unit.format(st.distanceMeters)})" }
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

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val prefs = vm.prefs
        val state = vm.state.value
        view.findViewById<RadioButton>(
            if (state.unit == DistanceUnit.MILES) R.id.unitMiles else R.id.unitKm
        ).isChecked = true
        view.findViewById<RadioButton>(
            if (prefs.chargerSource == ChargerSourceType.NREL) R.id.sourceNrel else R.id.sourceOcm
        ).isChecked = true
        val reserveLabel = view.findViewById<TextView>(R.id.reserveLabel)
        val reserve = view.findViewById<Slider>(R.id.reserveSlider)
        reserve.value = (state.settings.reservePercent / 5 * 5).toFloat().coerceIn(0f, 50f)
        reserveLabel.text = "Arrival reserve: ${reserve.value.toInt()}%"
        reserve.addOnChangeListener { _, v, _ -> reserveLabel.text = "Arrival reserve: ${v.toInt()}%" }
        val nrelKey = view.findViewById<EditText>(R.id.nrelKey)
        val ocmKey = view.findViewById<EditText>(R.id.ocmKey)
        nrelKey.setText(prefs.nrelKey.takeIf { it != "DEMO_KEY" })
        ocmKey.setText(prefs.ocmKey)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                vm.setUnit(if (view.findViewById<RadioButton>(R.id.unitKm).isChecked) DistanceUnit.KILOMETERS else DistanceUnit.MILES)
                vm.setReservePercent(reserve.value.toInt())
                prefs.chargerSource =
                    if (view.findViewById<RadioButton>(R.id.sourceOcm).isChecked) ChargerSourceType.OPEN_CHARGE_MAP
                    else ChargerSourceType.NREL
                prefs.nrelKey = nrelKey.text.toString()
                prefs.ocmKey = ocmKey.text.toString()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMyLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun startLocation() {
        val overlay = locationOverlay ?: MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
            locationOverlay = it
            map.overlays.add(it)
        }
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        map.controller.setZoom(12.0)
        overlay.runOnFirstFix {
            val fix = overlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                if (vm.state.value.waypoints.isEmpty()) vm.addWaypoint("My location", LatLon(fix.latitude, fix.longitude))
            }
        }
    }

    /** Fits the map to a newly planned route. */
    private fun zoomToStops(points: List<LatLon>) {
        if (points.size < 2) return
        val b = Geo.bounds(points)
        map.zoomToBoundingBox(BoundingBox(b.north, b.east, b.south, b.west), true, 120)
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
