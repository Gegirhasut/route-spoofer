package com.routespoofer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Foreground service that OWNS the route-playback clock and the OS mock-location
 * injection. It interpolates a position along the waypoint polyline by
 * elapsed-time x speed and pushes a fix into the GPS (+ NETWORK) test providers
 * every `intervalMs`. Because it runs as a `location`-typed foreground service it
 * keeps injecting while the app (and its WebView) are backgrounded.
 *
 * Every injected fix is also streamed back to the JS layer through [fixListener]
 * so the web preview marker / HUD / log follow this single clock.
 */
class MockLocationService : Service() {

  companion object {
    const val ACTION_START = "com.routespoofer.app.START"
    const val ACTION_PAUSE = "com.routespoofer.app.PAUSE"
    const val ACTION_RESUME = "com.routespoofer.app.RESUME"
    const val ACTION_STOP = "com.routespoofer.app.STOP"
    const val ACTION_SET_SPEED = "com.routespoofer.app.SET_SPEED"
    const val ACTION_SET_INTERVAL = "com.routespoofer.app.SET_INTERVAL"

    const val EXTRA_WAYPOINTS = "waypoints"   // JSON array string: [{lat,lng}, ...]
    const val EXTRA_SPEED = "speedKmh"
    const val EXTRA_INTERVAL = "intervalMs"
    const val EXTRA_LOOP = "loop"             // "off" | "restart" | "pingpong"

    private const val CHANNEL_ID = "route_spoofer_mock"
    private const val NOTIF_ID = 4711
    private const val EARTH_R = 6371000.0

    /** Set by the FakeGps plugin; receives every emitted fix as JSON. */
    @Volatile var fixListener: ((JSONObject) -> Unit)? = null
  }

  // ---- route ----
  private var waypoints: List<DoubleArray> = emptyList()   // [lat, lng]
  private var cum: DoubleArray = doubleArrayOf(0.0)         // cumulative metres
  private var total: Double = 0.0

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

  private lateinit var lm: LocationManager
  private var providersReady = false

  private val thread = HandlerThread("mock-clock").apply { start() }
  private val handler = Handler(thread.looper)

  private val tickRunnable = object : Runnable {
    override fun run() {
      if (!playing) return
      tick()
      if (playing) handler.postDelayed(this, intervalMs)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> handleStart(intent)
      ACTION_PAUSE -> handlePause()
      ACTION_RESUME -> handleResume()
      ACTION_STOP -> { handleStop(); return START_NOT_STICKY }
      ACTION_SET_SPEED -> { speedKmh = intent.getDoubleExtra(EXTRA_SPEED, speedKmh) }
      ACTION_SET_INTERVAL -> { intervalMs = max(50L, intent.getLongExtra(EXTRA_INTERVAL, intervalMs)) }
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

    startInForeground()      // notification text: notif_driving
    setupProviders()

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
    emitFix(sampleAt(traveled), false)
    updateNotification(R.string.notif_paused)
  }

  private fun handleResume() {
    if (playing || waypoints.isEmpty()) return
    playing = true
    lastTick = SystemClock.elapsedRealtime()
    updateNotification(R.string.notif_driving)
    handler.removeCallbacks(tickRunnable)
    handler.post(tickRunnable)
  }

  private fun handleStop() {
    playing = false
    handler.removeCallbacks(tickRunnable)
    teardownProviders()
    traveled = 0.0
    emitFix(sampleAt(0.0), false)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    playing = false
    handler.removeCallbacks(tickRunnable)
    teardownProviders()
    thread.quitSafely()
    super.onDestroy()
  }

  // ---------------------------------------------------------------- clock tick

  private fun tick() {
    val now = SystemClock.elapsedRealtime()
    val dt = (now - lastTick) / 1000.0
    if (playing) elapsedMs += (now - lastTick)
    lastTick = now

    if (waypoints.size > 1) {
      val v = speedKmh / 3.6 // m/s
      traveled += v * dt * dir
      if (traveled >= total) {
        when (loop) {
          "restart" -> traveled -= total
          "pingpong" -> { traveled = total - (traveled - total); dir = -1 }
          else -> { traveled = total; finishAtEnd(); return }
        }
      } else if (traveled <= 0 && dir == -1) {
        when (loop) {
          "pingpong" -> { traveled = -traveled; dir = 1 }
          "restart" -> traveled = total
          else -> traveled = 0.0
        }
      }
    }

    val fix = sampleAt(traveled)
    injectLocation(fix)
    emitFix(fix, true)
  }

  private fun finishAtEnd() {
    val fix = sampleAt(total)
    injectLocation(fix)
    emitFix(fix, false)
    playing = false
    handler.removeCallbacks(tickRunnable)
    updateNotification(R.string.notif_complete)
  }

  // ---------------------------------------------------------------- geo math

  private fun parseRoute(json: String?) {
    val list = ArrayList<DoubleArray>()
    if (json != null) {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        list.add(doubleArrayOf(o.getDouble("lat"), o.getDouble("lng")))
      }
    }
    waypoints = list
    cum = DoubleArray(max(1, list.size))
    cum[0] = 0.0
    for (i in 1 until list.size) cum[i] = cum[i - 1] + haversine(list[i - 1], list[i])
    total = if (cum.isNotEmpty()) cum[cum.size - 1] else 0.0
  }

  private fun haversine(a: DoubleArray, b: DoubleArray): Double {
    val dLat = Math.toRadians(b[0] - a[0])
    val dLng = Math.toRadians(b[1] - a[1])
    val s = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(a[0])) * cos(Math.toRadians(b[0])) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_R * Math.asin(sqrt(s))
  }

  private fun bearing(a: DoubleArray, b: DoubleArray): Double {
    val y = sin(Math.toRadians(b[1] - a[1])) * cos(Math.toRadians(b[0]))
    val x = cos(Math.toRadians(a[0])) * sin(Math.toRadians(b[0])) -
      sin(Math.toRadians(a[0])) * cos(Math.toRadians(b[0])) * cos(Math.toRadians(b[1] - a[1]))
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
  }

  /** Position + heading at arclength s (metres) along the route. */
  private fun sampleAt(sIn: Double): Fix {
    if (waypoints.isEmpty()) return Fix(0.0, 0.0, 0.0, 0)
    if (waypoints.size == 1) return Fix(waypoints[0][0], waypoints[0][1], 0.0, 0)
    val s = max(0.0, min(sIn, total))
    var i = 0
    while (i < cum.size - 2 && s > cum[i + 1]) i++
    val a = waypoints[i]; val b = waypoints[i + 1]
    val segLen = (cum[i + 1] - cum[i]).let { if (it == 0.0) 1.0 else it }
    val t = (s - cum[i]) / segLen
    return Fix(
      lat = a[0] + (b[0] - a[0]) * t,
      lng = a[1] + (b[1] - a[1]) * t,
      bearing = bearing(a, b),
      seg = i
    )
  }

  data class Fix(val lat: Double, val lng: Double, val bearing: Double, val seg: Int)

  // ---------------------------------------------------------------- injection

  private fun setupProviders() {
    if (providersReady) return
    addProvider(LocationManager.GPS_PROVIDER)
    addProvider(LocationManager.NETWORK_PROVIDER)
    providersReady = true
  }

  private fun addProvider(name: String) {
    try {
      try { lm.removeTestProvider(name) } catch (_: Exception) {}
      @Suppress("DEPRECATION")
      lm.addTestProvider(
        name,
        false, false, false, false,
        true, true, true,
        android.location.Criteria.POWER_LOW,
        android.location.Criteria.ACCURACY_FINE
      )
      lm.setTestProviderEnabled(name, true)
    } catch (e: SecurityException) {
      // Not selected as the mock-location app — nothing we can do here.
    } catch (e: Exception) {
      // Provider may not exist on this device (e.g. NETWORK) — ignore.
    }
  }

  private fun teardownProviders() {
    if (!providersReady) return
    for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
      try { lm.setTestProviderEnabled(name, false) } catch (_: Exception) {}
      try { lm.removeTestProvider(name) } catch (_: Exception) {}
    }
    providersReady = false
  }

  private fun injectLocation(fix: Fix) {
    if (waypoints.isEmpty()) return
    val speedMs = (if (playing) speedKmh else 0.0) / 3.6
    for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
      val loc = Location(name).apply {
        latitude = fix.lat
        longitude = fix.lng
        altitude = 0.0
        accuracy = 4f
        bearing = fix.bearing.toFloat()
        speed = speedMs.toFloat()
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          bearingAccuracyDegrees = 1f
          speedAccuracyMetersPerSecond = 1f
          verticalAccuracyMeters = 3f
        }
      }
      try { lm.setTestProviderLocation(name, loc) } catch (_: Exception) {}
    }
  }

  // ---------------------------------------------------------------- emit to JS

  private fun emitFix(fix: Fix, isPlaying: Boolean) {
    val progress = if (total > 0) traveled / total else 0.0
    val json = JSONObject().apply {
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
        this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      )
    } else {
      startForeground(NOTIF_ID, notif)
    }
  }

  /** [textRes] is a localized status string (notif_driving / notif_paused / notif_complete). */
  private fun buildNotification(textRes: Int): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pi = PendingIntent.getActivity(
      this, 0, launch,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.app_name))   // "Route Spoofer" — brand, not localized
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
      val ch = NotificationChannel(
        CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW
      ).apply { description = getString(R.string.notif_channel_desc) }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(ch)
    }
  }
}
