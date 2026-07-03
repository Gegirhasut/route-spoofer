package com.routespoofer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Foreground service that owns the playback CLOCK and the OS mock-location
 * injection. Broadcasting and movement are independent:
 *
 *  - GPS ON/OFF (start/stop) is the only thing that starts/stops emission. While
 *    ON the clock ticks every `intervalMs`, steps [RouteEngine] and emits the
 *    current position — whether the driver is standing, driving, waiting or
 *    arrived (speed is 0 unless actually driving).
 *  - GO / Pause / Stop / live speed / dwell / append only move the cursor or
 *    change how it moves; they never start or stop emission.
 *
 * [RouteEngine] is the pure source of geometry + hold/wake state; the injector is
 * unchanged. Every emitted fix is streamed back to JS via [fixListener].
 */
class MockLocationService : Service() {
    private var engine: RouteEngine = RouteEngine(emptyList())

    private var speedKmh: Double = 40.0
    private var intervalMs: Long = 1000
    private var gpsOn: Boolean = false
    private var elapsedMs: Long = 0
    private var lastTick: Long = 0

    private lateinit var injector: MockLocationInjector

    private val thread = HandlerThread("mock-clock").apply { start() }
    private val handler = Handler(thread.looper)

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (!gpsOn) return
                tick()
                if (gpsOn) handler.postDelayed(this, intervalMs)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        injector = MockLocationInjector(applicationContext)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_GPS -> startGps()
            ACTION_STOP_GPS -> stopGps()
            ACTION_SET_ROUTE -> setRoute(intent)
            ACTION_GO -> engine.go()
            ACTION_PAUSE -> engine.pause()
            ACTION_STOP -> {
                engine.stop()
                elapsedMs = 0
            }
            ACTION_SET_SPEED -> speedKmh = intent.getDoubleExtra(EXTRA_SPEED, speedKmh)
            ACTION_SET_INTERVAL -> intervalMs = max(MIN_INTERVAL_MS, intent.getLongExtra(EXTRA_INTERVAL, intervalMs))
            ACTION_SET_LOOP -> engine.loop = LoopMode.fromId(intent.getStringExtra(EXTRA_LOOP))
            ACTION_APPEND_WAYPOINT ->
                engine.appendPoint(LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LNG, 0.0)))
            ACTION_SET_WAYPOINT -> editWaypoint(intent)
            ACTION_REMOVE_WAYPOINT -> engine.removeWaypoint(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_MOVE_WAYPOINT ->
                engine.moveWaypoint(
                    intent.getIntExtra(EXTRA_INDEX, -1),
                    LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LNG, 0.0)),
                )
        }
        // Push an immediate snapshot so the UI reflects commands without waiting a tick.
        if (gpsOn) emit(engine.state(speedKmh)) else fixListener?.invoke(emitJson(engine.state(speedKmh)))
        return START_STICKY
    }

    // ---------------------------------------------------------------- GPS toggle

    private fun startGps() {
        if (gpsOn) return
        gpsOn = true
        startInForeground()
        if (!injector.setup()) {
            // Surface (don't silently no-op): the LM test providers did not register,
            // typically because the app is not the selected mock-location app.
            Log.w(TAG, "Mock providers did not fully initialize on start")
        }
        lastTick = SystemClock.elapsedRealtime()
        tick()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, intervalMs)
    }

    private fun stopGps() {
        if (!gpsOn) return
        gpsOn = false
        handler.removeCallbacks(tickRunnable)
        injector.teardown()
        // Stop emitting but keep the service (and the cursor) alive.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        emit(engine.state(speedKmh)) // a final, not-emitting snapshot for the UI
    }

    override fun onDestroy() {
        gpsOn = false
        handler.removeCallbacks(tickRunnable)
        injector.teardown()
        thread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- clock tick

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dtMs = now - lastTick
        lastTick = now
        val st = engine.step(dtMs, speedKmh)
        if (st.phase == Phase.DRIVING) elapsedMs += dtMs
        injector.inject(Fix(st.lat, st.lng, st.bearing, st.seg), st.speedKmh / MS_PER_KMH)
        emit(st)
        updateNotification(notifTextFor(st.phase))
    }

    // ---------------------------------------------------------------- route input

    private fun setRoute(intent: Intent) {
        engine = RouteEngine(parseWaypoints(intent.getStringExtra(EXTRA_WAYPOINTS)))
        engine.loop = LoopMode.fromId(intent.getStringExtra(EXTRA_LOOP))
        speedKmh = intent.getDoubleExtra(EXTRA_SPEED, speedKmh)
        intervalMs = max(MIN_INTERVAL_MS, intent.getLongExtra(EXTRA_INTERVAL, intervalMs))
        elapsedMs = 0
    }

    private fun editWaypoint(intent: Intent) {
        val i = intent.getIntExtra(EXTRA_INDEX, -1)
        if (i < 0) return
        if (intent.hasExtra(EXTRA_DWELL)) {
            engine.setWaypointDwell(i, DwellKind.fromId(intent.getStringExtra(EXTRA_DWELL)), intent.getLongExtra(EXTRA_DWELL_MS, 0L))
        }
        if (intent.hasExtra(EXTRA_LEG_SPEED)) {
            val s = intent.getDoubleExtra(EXTRA_LEG_SPEED, -1.0)
            engine.setLegSpeed(i, if (s > 0) s else null)
        }
    }

    private fun parseWaypoints(json: String?): List<Waypoint> {
        val list = ArrayList<Waypoint>()
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val legSpeed = if (o.has("legSpeedKmh") && !o.isNull("legSpeedKmh")) o.getDouble("legSpeedKmh") else null
                list.add(
                    Waypoint(
                        pos = LatLng(o.getDouble("lat"), o.getDouble("lng")),
                        dwell = DwellKind.fromId(o.optString("dwell", "none")),
                        dwellMs = o.optLong("dwellMs", 0L),
                        legSpeedKmh = legSpeed,
                    ),
                )
            }
        }
        return list
    }

    // ---------------------------------------------------------------- emit to JS

    private fun emit(st: DriveState) = fixListener?.invoke(emitJson(st))

    private fun emitJson(st: DriveState): JSONObject =
        JSONObject().apply {
            put("lat", round6(st.lat))
            put("lng", round6(st.lng))
            put("bearing", st.bearing)
            put("speedKmh", st.speedKmh)
            put("progress", st.progress)
            put("segIndex", st.seg)
            put("traveled", st.traveled)
            put("phase", st.phase.name.lowercase())
            put("holdRemainingMs", st.holdRemainingMs)
            put("holdWaypoint", st.holdWaypoint)
            put("elapsedMs", elapsedMs)
            put("gpsOn", gpsOn)
            put("driving", st.phase == Phase.DRIVING)
        }

    private fun round6(v: Double): Double = Math.round(v * ROUND_SCALE) / ROUND_SCALE

    // ---------------------------------------------------------------- foreground

    private fun notifTextFor(phase: Phase): Int =
        when (phase) {
            Phase.DRIVING, Phase.STANDING -> R.string.notif_driving
            Phase.WAITING_TIMED, Phase.WAITING_GO -> R.string.notif_paused
            Phase.ARRIVED -> R.string.notif_complete
        }

    private fun startInForeground() {
        val notif = buildNotification(R.string.notif_driving)
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            sdk >= Build.VERSION_CODES.Q ->
                ServiceCompat.startForeground(this, NOTIF_ID, notif, 0)
            else -> startForeground(NOTIF_ID, notif)
        }
    }

    /** [textRes] is a localized status string (notif_driving / notif_paused / notif_complete). */
    private fun buildNotification(textRes: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi =
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(textRes: Int) {
        if (!gpsOn) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(textRes))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // The old channel badged the launcher icon (#4562); a channel's badge
            // setting is immutable once created, so drop the legacy one and recreate
            // under a new id with badging off.
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val ch =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val ACTION_START_GPS = "com.routespoofer.app.START_GPS"
        const val ACTION_STOP_GPS = "com.routespoofer.app.STOP_GPS"
        const val ACTION_SET_ROUTE = "com.routespoofer.app.SET_ROUTE"
        const val ACTION_GO = "com.routespoofer.app.GO"
        const val ACTION_PAUSE = "com.routespoofer.app.PAUSE"
        const val ACTION_STOP = "com.routespoofer.app.STOP"
        const val ACTION_SET_SPEED = "com.routespoofer.app.SET_SPEED"
        const val ACTION_SET_INTERVAL = "com.routespoofer.app.SET_INTERVAL"
        const val ACTION_SET_LOOP = "com.routespoofer.app.SET_LOOP"
        const val ACTION_APPEND_WAYPOINT = "com.routespoofer.app.APPEND_WAYPOINT"
        const val ACTION_SET_WAYPOINT = "com.routespoofer.app.SET_WAYPOINT"
        const val ACTION_REMOVE_WAYPOINT = "com.routespoofer.app.REMOVE_WAYPOINT"
        const val ACTION_MOVE_WAYPOINT = "com.routespoofer.app.MOVE_WAYPOINT"

        const val EXTRA_WAYPOINTS = "waypoints"
        const val EXTRA_SPEED = "speedKmh"
        const val EXTRA_INTERVAL = "intervalMs"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_INDEX = "index"
        const val EXTRA_DWELL = "dwell"
        const val EXTRA_DWELL_MS = "dwellMs"
        const val EXTRA_LEG_SPEED = "legSpeedKmh"

        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "route_spoofer_mock2"
        private const val LEGACY_CHANNEL_ID = "route_spoofer_mock"
        private const val NOTIF_ID = 4711
        private const val MIN_INTERVAL_MS = 50L
        private const val MS_PER_KMH = 3.6
        private const val ROUND_SCALE = 1e6

        /** Set by the FakeGps plugin; receives every emitted fix as JSON. */
        @Volatile
        var fixListener: ((JSONObject) -> Unit)? = null
    }
}
