package com.routespoofer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that OWNS the route-playback clock and the OS mock-location
 * injection. It interpolates a position along the waypoint polyline by
 * elapsed-time x speed (via [RouteEngine]) and pushes a fix into the GPS (+
 * NETWORK) test providers (via [MockLocationInjector]) every `intervalMs`.
 * Because it runs as a `location`-typed foreground service it keeps injecting
 * while the app (and its WebView) are backgrounded.
 *
 * Every injected fix is also streamed back to the JS layer through [fixListener]
 * so the web preview marker / HUD / log follow this single clock.
 */
class MockLocationService : Service() {
    // ---- route ----
    private var engine: RouteEngine = RouteEngine(emptyList())

    // ---- playback params ----
    private var speedKmh: Double = 40.0
    private var intervalMs: Long = 1000
    private var loop: String = "off"

    // ---- playback state ----
    private var traveled: Double = 0.0
    private var dir: Int = 1
    private var playing: Boolean = false
    private var elapsedMs: Long = 0
    private var lastTick: Long = 0

    private lateinit var injector: MockLocationInjector

    private val thread = HandlerThread("mock-clock").apply { start() }
    private val handler = Handler(thread.looper)

    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (!playing) return
                tick()
                if (playing) handler.postDelayed(this, intervalMs)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        injector = MockLocationInjector(lm)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }
            ACTION_SET_SPEED -> speedKmh = intent.getDoubleExtra(EXTRA_SPEED, speedKmh)
            ACTION_SET_INTERVAL -> intervalMs = max(50L, intent.getLongExtra(EXTRA_INTERVAL, intervalMs))
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- start/stop

    private fun handleStart(intent: Intent) {
        parseRoute(intent.getStringExtra(EXTRA_WAYPOINTS))
        speedKmh = intent.getDoubleExtra(EXTRA_SPEED, 40.0)
        intervalMs = max(50L, intent.getLongExtra(EXTRA_INTERVAL, 1000L))
        loop = intent.getStringExtra(EXTRA_LOOP) ?: "off"

        traveled = 0.0
        dir = 1
        elapsedMs = 0
        playing = true

        startInForeground() // notification text: notif_driving
        injector.setup()

        lastTick = SystemClock.elapsedRealtime()
        // emit an immediate first fix, then run on the interval clock
        tick()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, intervalMs)
    }

    private fun handlePause() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(tickRunnable)
        emitFix(engine.sampleAt(traveled), false)
        updateNotification(R.string.notif_paused)
    }

    private fun handleResume() {
        if (playing || engine.waypoints.isEmpty()) return
        playing = true
        lastTick = SystemClock.elapsedRealtime()
        updateNotification(R.string.notif_driving)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun handleStop() {
        playing = false
        handler.removeCallbacks(tickRunnable)
        injector.teardown()
        traveled = 0.0
        emitFix(engine.sampleAt(0.0), false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        playing = false
        handler.removeCallbacks(tickRunnable)
        injector.teardown()
        thread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- clock tick

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick) / 1000.0
        if (playing) elapsedMs += (now - lastTick)
        lastTick = now

        val delta = (speedKmh / 3.6) * dt // metres this step (m/s x s)
        val adv = engine.advance(traveled, dir, delta, LoopMode.fromId(loop))
        traveled = adv.traveled
        dir = adv.dir
        if (adv.atEnd) {
            finishAtEnd()
            return
        }

        val fix = engine.sampleAt(traveled)
        injectLocation(fix)
        emitFix(fix, true)
    }

    private fun finishAtEnd() {
        val fix = engine.sampleAt(engine.totalDistance)
        injectLocation(fix)
        emitFix(fix, false)
        playing = false
        handler.removeCallbacks(tickRunnable)
        updateNotification(R.string.notif_complete)
    }

    // ---------------------------------------------------------------- route input

    private fun parseRoute(json: String?) {
        val list = ArrayList<LatLng>()
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(LatLng(o.getDouble("lat"), o.getDouble("lng")))
            }
        }
        engine = RouteEngine(list)
    }

    // ---------------------------------------------------------------- injection

    private fun injectLocation(fix: Fix) {
        if (engine.waypoints.isEmpty()) return
        val speedMs = (if (playing) speedKmh else 0.0) / 3.6
        injector.inject(fix, speedMs)
    }

    // ---------------------------------------------------------------- emit to JS

    private fun emitFix(
        fix: Fix,
        isPlaying: Boolean,
    ) {
        val progress = if (engine.totalDistance > 0) traveled / engine.totalDistance else 0.0
        val json =
            JSONObject().apply {
                put("lat", round6(fix.lat))
                put("lng", round6(fix.lng))
                put("bearing", fix.bearing)
                put("speedKmh", if (isPlaying) speedKmh else 0.0)
                put("progress", max(0.0, min(1.0, progress)))
                put("segIndex", fix.seg)
                put("elapsedMs", elapsedMs)
                put("playing", isPlaying)
            }
        fixListener?.invoke(json)
    }

    private fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6

    // ---------------------------------------------------------------- foreground

    private fun startInForeground() {
        val notif = buildNotification(R.string.notif_driving)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
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
            .setContentTitle(getString(R.string.app_name)) // "Route Spoofer" — brand, not localized
            .setContentText(getString(textRes))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(textRes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(textRes))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.notif_channel_desc) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val ACTION_START = "com.routespoofer.app.START"
        const val ACTION_PAUSE = "com.routespoofer.app.PAUSE"
        const val ACTION_RESUME = "com.routespoofer.app.RESUME"
        const val ACTION_STOP = "com.routespoofer.app.STOP"
        const val ACTION_SET_SPEED = "com.routespoofer.app.SET_SPEED"
        const val ACTION_SET_INTERVAL = "com.routespoofer.app.SET_INTERVAL"

        const val EXTRA_WAYPOINTS = "waypoints" // JSON array string: [{lat,lng}, ...]
        const val EXTRA_SPEED = "speedKmh"
        const val EXTRA_INTERVAL = "intervalMs"
        const val EXTRA_LOOP = "loop" // "off" | "restart" | "pingpong"

        private const val CHANNEL_ID = "route_spoofer_mock"
        private const val NOTIF_ID = 4711

        /** Set by the FakeGps plugin; receives every emitted fix as JSON. */
        @Volatile
        var fixListener: ((JSONObject) -> Unit)? = null
    }
}
