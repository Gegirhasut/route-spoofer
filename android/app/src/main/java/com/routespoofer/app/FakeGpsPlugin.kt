package com.routespoofer.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.json.JSONArray

/**
 * Capacitor bridge for the route player. All heavy lifting (playback clock,
 * mock-location injection) lives in [MockLocationService]; this plugin only
 * marshals calls/params to the service and streams the service's `fix` events
 * back to JS via [notifyListeners].
 */
@CapacitorPlugin(
  name = "FakeGps",
  permissions = [
    Permission(
      alias = "location",
      strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
    ),
    Permission(
      alias = "notifications",
      strings = [Manifest.permission.POST_NOTIFICATIONS]
    )
  ]
)
class FakeGpsPlugin : Plugin() {

  override fun load() {
    // Single source of truth: forward every native fix to the JS listener.
    MockLocationService.fixListener = { json ->
      try { notifyListeners("fix", JSObject(json.toString())) } catch (_: Exception) {}
    }
  }

  override fun handleOnDestroy() {
    MockLocationService.fixListener = null
    super.handleOnDestroy()
  }

  // ----------------------------------------------------------------- playback

  @PluginMethod
  fun start(call: PluginCall) {
    val wps: JSONArray = call.getArray("waypoints") ?: JSONArray()
    val intent = serviceIntent(MockLocationService.ACTION_START).apply {
      putExtra(MockLocationService.EXTRA_WAYPOINTS, wps.toString())
      putExtra(MockLocationService.EXTRA_SPEED, call.getDouble("speedKmh") ?: 40.0)
      putExtra(MockLocationService.EXTRA_INTERVAL, (call.getInt("intervalMs") ?: 1000).toLong())
      putExtra(MockLocationService.EXTRA_LOOP, call.getString("loop") ?: "off")
    }
    startService(intent)
    call.resolve()
  }

  @PluginMethod
  fun pause(call: PluginCall) {
    startService(serviceIntent(MockLocationService.ACTION_PAUSE)); call.resolve()
  }

  @PluginMethod
  fun resume(call: PluginCall) {
    startService(serviceIntent(MockLocationService.ACTION_RESUME)); call.resolve()
  }

  @PluginMethod
  fun stop(call: PluginCall) {
    startService(serviceIntent(MockLocationService.ACTION_STOP)); call.resolve()
  }

  @PluginMethod
  fun setSpeed(call: PluginCall) {
    val intent = serviceIntent(MockLocationService.ACTION_SET_SPEED)
      .putExtra(MockLocationService.EXTRA_SPEED, call.getDouble("speedKmh") ?: 40.0)
    startService(intent); call.resolve()
  }

  @PluginMethod
  fun setInterval(call: PluginCall) {
    val intent = serviceIntent(MockLocationService.ACTION_SET_INTERVAL)
      .putExtra(MockLocationService.EXTRA_INTERVAL, (call.getInt("intervalMs") ?: 1000).toLong())
    startService(intent); call.resolve()
  }

  // ----------------------------------------------------------------- readiness

  @PluginMethod
  fun ensureReady(call: PluginCall) {
    val locationOk = getPermissionState("location") == PermissionState.GRANTED
    val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
      getPermissionState("notifications") == PermissionState.GRANTED else true
    val mockOk = isMockAppSelected()

    val res = JSObject()
    res.put("locationPermission", locationOk)
    res.put("notificationPermission", notifOk)
    res.put("mockAppEnabled", mockOk)
    res.put("ready", locationOk && notifOk && mockOk)
    call.resolve(res)
  }

  /**
   * Probe whether we are the selected system mock-location app by attempting to
   * register a test provider. A [SecurityException] means we are NOT selected.
   */
  private fun isMockAppSelected(): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
      try { lm.removeTestProvider(PROBE_PROVIDER) } catch (_: Exception) {}
      @Suppress("DEPRECATION")
      lm.addTestProvider(
        PROBE_PROVIDER,
        false, false, false, false,
        true, true, true,
        android.location.Criteria.POWER_LOW,
        android.location.Criteria.ACCURACY_FINE
      )
      try { lm.removeTestProvider(PROBE_PROVIDER) } catch (_: Exception) {}
      true
    } catch (e: SecurityException) {
      false
    } catch (e: Exception) {
      // Some OEMs throw IllegalArgumentException on the probe name while still
      // allowing mocking; treat non-security failures as "selected".
      true
    }
  }

  @PluginMethod
  fun openDevSettings(call: PluginCall) {
    try {
      val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: Exception) {
      val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
    call.resolve()
  }

  // ----------------------------------------------------------------- helpers

  private fun serviceIntent(action: String): Intent =
    Intent(context, MockLocationService::class.java).setAction(action)

  private fun startService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      intent.action == MockLocationService.ACTION_START
    ) {
      context.startForegroundService(intent)
    } else {
      // pause/resume/stop/setX target an already-running foreground service
      context.startService(intent)
    }
  }

  companion object {
    private const val PROBE_PROVIDER = "route-spoofer-probe"
  }
}
