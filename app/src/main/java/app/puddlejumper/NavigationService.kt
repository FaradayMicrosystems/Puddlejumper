package app.puddlejumper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.puddlejumper.core.Guidance
import app.puddlejumper.core.LatLon
import app.puddlejumper.core.NavFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps turn-by-turn guidance running with the screen off or another app in front: reads GPS, feeds
 * [NavSession], speaks its prompts, and shows the next turn in a notification (Android requires one).
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var voice: NavVoice
    private var listener: LocationListener? = null
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        voice = NavVoice(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Next turn while navigating"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavSession.stop(this)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting navigation", "Waiting for GPS…"), type)
        if (NavSession.state.value == null || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (listener == null) startUpdates()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startUpdates() {
        val lm = getSystemService(LocationManager::class.java)
        // A full object, not a lambda: before Android 11 the other callbacks have no default and would crash.
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                voice.say("GPS is off. Turn on location to keep guiding.")
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())

        scope.launch {
            NavSession.speech.collect { text -> if (NavSession.state.value?.muted == false) voice.say(text) }
        }
        scope.launch {
            var wasMuted = NavSession.state.value?.muted ?: false
            NavSession.state.collect { nav ->
                if (nav == null) {
                    stopSelf()
                    return@collect
                }
                if (nav.muted && !wasMuted) voice.stop()
                wasMuted = nav.muted
                updateNotification(nav)
            }
        }
    }

    private fun onLocation(loc: Location) {
        NavSession.onFix(
            NavFix(
                location = LatLon(loc.latitude, loc.longitude),
                timeMs = System.currentTimeMillis(),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 30.0,
                bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            )
        )
    }

    private fun updateNotification(nav: NavUi) {
        val p = nav.progress
        val (title, text) = when {
            nav.rerouting -> "Rerouting…" to "Finding a new route from here"
            p == null -> "Navigating" to "Waiting for GPS…"
            p.arrived -> "Arrived" to "Trip finished"
            else -> {
                val b = p.banner
                val arrow = b?.let { Guidance.arrow(it.maneuver) } ?: "↑"
                "$arrow ${Guidance.shortDistance(p.distanceToManeuverM)}  ${b?.text.orEmpty()}" to
                    "${Guidance.shortDistance(p.distanceRemainingM)} · ${Guidance.duration(p.durationRemainingS)} left"
            }
        }
        // Distances change every second; only post when the shown text changes.
        val key = "$title|$text"
        if (key == lastText) return
        lastText = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "End", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        scope.cancel()
        voice.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "app.puddlejumper.STOP_NAVIGATION"
    }
}
