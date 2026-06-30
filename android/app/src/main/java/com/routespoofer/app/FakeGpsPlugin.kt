package com.routespoofer.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
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
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION],
        ),
        Permission(
            alias = "notifications",
            strings = [Manifest.permission.POST_NOTIFICATIONS],
        ),
    ],
)
class FakeGpsPlugin : Plugin() {
    override fun load() {
        // Single source of truth: forward every native fix to the JS listener.
        MockLocationService.fixListener = { json ->
            try {
                notifyListeners("fix", JSObject(json.toString()))
            } catch (_: Exception) {
            }
        }
    }

    override fun handleOnDestroy() {
        MockLocationService.fixListener = null
        super.handleOnDestroy()
    }

    // ----------------------------------------------------------------- broadcasting

    /** Turn GPS emission ON (foreground service). The only thing that starts emitting. */
    @PluginMethod
    fun startGps(call: PluginCall) {
        startService(serviceIntent(MockLocationService.ACTION_START_GPS))
        call.resolve()
    }

    /** Turn GPS emission OFF. The only thing that stops emitting. */
    @PluginMethod
    fun stopGps(call: PluginCall) {
        startService(serviceIntent(MockLocationService.ACTION_STOP_GPS))
        call.resolve()
    }

    /** Define / replace the route (waypoints carry optional dwell + per-leg speed). */
    @PluginMethod
    fun setRoute(call: PluginCall) {
        val wps: JSONArray = call.getArray("waypoints") ?: JSONArray()
        val intent =
            serviceIntent(MockLocationService.ACTION_SET_ROUTE).apply {
                putExtra(MockLocationService.EXTRA_WAYPOINTS, wps.toString())
                putExtra(MockLocationService.EXTRA_SPEED, call.getDouble("speedKmh") ?: DEFAULT_SPEED)
                putExtra(MockLocationService.EXTRA_INTERVAL, (call.getInt("intervalMs") ?: DEFAULT_INTERVAL).toLong())
                putExtra(MockLocationService.EXTRA_LOOP, call.getString("loop") ?: "off")
            }
        startService(intent)
        call.resolve()
    }

    // ----------------------------------------------------------------- movement

    /** Drive the cursor along the route; ends any hold immediately (GO). */
    @PluginMethod
    fun go(call: PluginCall) {
        startService(serviceIntent(MockLocationService.ACTION_GO))
        call.resolve()
    }

    /** Stand still and keep broadcasting until GO (manual hold). */
    @PluginMethod
    fun pause(call: PluginCall) {
        startService(serviceIntent(MockLocationService.ACTION_PAUSE))
        call.resolve()
    }

    /** Reset the cursor to the route start. Does NOT stop GPS. */
    @PluginMethod
    fun stop(call: PluginCall) {
        startService(serviceIntent(MockLocationService.ACTION_STOP))
        call.resolve()
    }

    /** Live base-speed change (applies mid-leg). */
    @PluginMethod
    fun setSpeed(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_SET_SPEED)
                .putExtra(MockLocationService.EXTRA_SPEED, call.getDouble("speedKmh") ?: DEFAULT_SPEED),
        )
        call.resolve()
    }

    @PluginMethod
    fun setInterval(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_SET_INTERVAL)
                .putExtra(MockLocationService.EXTRA_INTERVAL, (call.getInt("intervalMs") ?: DEFAULT_INTERVAL).toLong()),
        )
        call.resolve()
    }

    @PluginMethod
    fun setLoop(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_SET_LOOP)
                .putExtra(MockLocationService.EXTRA_LOOP, call.getString("loop") ?: "off"),
        )
        call.resolve()
    }

    /** Append a waypoint to the end of the route (live, beyond the cursor). */
    @PluginMethod
    fun appendWaypoint(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_APPEND_WAYPOINT)
                .putExtra(MockLocationService.EXTRA_LAT, call.getDouble("lat") ?: 0.0)
                .putExtra(MockLocationService.EXTRA_LNG, call.getDouble("lng") ?: 0.0),
        )
        call.resolve()
    }

    /** Move a future (unlocked) waypoint. */
    @PluginMethod
    fun moveWaypoint(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_MOVE_WAYPOINT)
                .putExtra(MockLocationService.EXTRA_INDEX, call.getInt("index") ?: -1)
                .putExtra(MockLocationService.EXTRA_LAT, call.getDouble("lat") ?: 0.0)
                .putExtra(MockLocationService.EXTRA_LNG, call.getDouble("lng") ?: 0.0),
        )
        call.resolve()
    }

    /** Remove a future (unlocked) waypoint. */
    @PluginMethod
    fun removeWaypoint(call: PluginCall) {
        startService(
            serviceIntent(MockLocationService.ACTION_REMOVE_WAYPOINT)
                .putExtra(MockLocationService.EXTRA_INDEX, call.getInt("index") ?: -1),
        )
        call.resolve()
    }

    /** Edit a future waypoint's dwell and/or per-leg speed. */
    @PluginMethod
    fun setWaypoint(call: PluginCall) {
        val intent =
            serviceIntent(MockLocationService.ACTION_SET_WAYPOINT)
                .putExtra(MockLocationService.EXTRA_INDEX, call.getInt("index") ?: -1)
        call.getString("dwell")?.let {
            intent.putExtra(MockLocationService.EXTRA_DWELL, it)
            intent.putExtra(MockLocationService.EXTRA_DWELL_MS, (call.getInt("dwellMs") ?: 0).toLong())
        }
        if (call.data.has("legSpeedKmh")) {
            intent.putExtra(MockLocationService.EXTRA_LEG_SPEED, call.getDouble("legSpeedKmh") ?: -1.0)
        }
        startService(intent)
        call.resolve()
    }

    // ----------------------------------------------------------------- readiness

    @PluginMethod
    fun ensureReady(call: PluginCall) {
        val locationOk = getPermissionState("location") == PermissionState.GRANTED
        val notifOk =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPermissionState("notifications") == PermissionState.GRANTED
            } else {
                true
            }
        val mockOk = isMockAppSelected()
        val devOk = isDevOptionsOn()

        // Keep a persistent, dismissible nudge alive only while dev options are off,
        // so the guidance survives the user leaving the app to flip the setting.
        if (devOk) clearDevOptionsNotice() else postDevOptionsNotice()

        val res = JSObject()
        res.put("locationPermission", locationOk)
        res.put("notificationPermission", notifOk)
        res.put("mockAppEnabled", mockOk)
        res.put("devOptionsEnabled", devOk)
        res.put("ready", locationOk && notifOk && mockOk)
        call.resolve(res)
    }

    /** Whether Android Developer options are enabled (the gate for mock-app selection). */
    @PluginMethod
    fun isDevOptionsEnabled(call: PluginCall) {
        val res = JSObject()
        res.put("enabled", isDevOptionsOn())
        call.resolve(res)
    }

    /**
     * Probe whether we are the selected system mock-location app by attempting to
     * register a test provider. A [SecurityException] means we are NOT selected.
     */
    private fun isMockAppSelected(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            try {
                lm.removeTestProvider(PROBE_PROVIDER)
            } catch (_: Exception) {
            }
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                PROBE_PROVIDER,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE,
            )
            try {
                lm.removeTestProvider(PROBE_PROVIDER)
            } catch (_: Exception) {
            }
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
        // Developer options host the "Select mock location app" picker. Guarded the
        // same way as openUrl(): a ROM that can't resolve the specific intent
        // (ActivityNotFoundException) falls back to the general Settings screen, and
        // reports opened=false instead of crashing if even that can't resolve, so
        // the web layer can degrade to an in-app hint (#4528).
        val opened = openSettingsScreen(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        call.resolve(JSObject().put("opened", opened))
    }

    /**
     * Open a specific system settings screen, guarded like openUrl(): try the
     * requested action, then fall back to the top-level Settings screen, returning
     * false (never throwing) if neither can be resolved on this device/ROM.
     */
    private fun openSettingsScreen(action: String): Boolean =
        startSettingsActivity(action) || startSettingsActivity(Settings.ACTION_SETTINGS)

    private fun startSettingsActivity(action: String): Boolean =
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Open the "About phone" / device-info screen so the user lands near the
     * Build number tap target. Falls back to the top-level Settings screen.
     */
    @PluginMethod
    fun openAboutPhone(call: PluginCall) {
        try {
            val intent =
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
        call.resolve()
    }

    /**
     * Deep-link to this app's "App info" screen so the user can re-grant a
     * location permission that was revoked (manually, or by Android auto-revoke
     * for unused apps). Used when a runtime request can no longer prompt.
     */
    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
        call.resolve()
    }

    /**
     * Open an external URL via a guarded ACTION_VIEW. The intent dispatch is
     * wrapped so a missing/disabled browser (ActivityNotFoundException) can
     * never crash the app — it resolves { opened: false } and the web layer
     * falls back to copy-to-clipboard instead.
     */
    @PluginMethod
    fun openUrl(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.resolve(JSObject().put("opened", false))
            return
        }
        call.resolve(JSObject().put("opened", launchView(url)))
    }

    /**
     * Launch an external ACTION_VIEW for [url] and report an HONEST result: true ONLY when
     * a handler actually resolves AND the launch is issued from a valid path. A launch the
     * OS silently suppresses (ColorOS / Android 10 background-activity-start) without
     * throwing must NOT be reported as success, so the web layer can fall back (#4520).
     * Prefers a true foreground start from the Activity; uses the app context + NEW_TASK
     * only when no Activity is attached.
     */
    private fun launchView(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val host = activity
        return try {
            if (host != null) {
                if (intent.resolveActivity(host.packageManager) == null) return false
                host.startActivity(intent) // real foreground start (no NEW_TASK off an Activity)
                true
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) return false
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "openUrl: could not open $url", e)
            false
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun isDevOptionsOn(): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1

    /**
     * Low-priority, dismissible reminder shown while Developer options are off.
     * Tapping it reopens Route Spoofer so the readiness guidance is one tap away.
     */
    private fun postDevOptionsNotice() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch =
                NotificationChannel(
                    DEV_CHANNEL_ID,
                    context.getString(R.string.devnotif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.devnotif_channel_desc) }
            nm.createNotificationChannel(ch)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi =
            PendingIntent.getActivity(
                context,
                1,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notif =
            NotificationCompat.Builder(context, DEV_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.devnotif_title))
                .setContentText(context.getString(R.string.devnotif_text))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        try {
            nm.notify(DEV_NOTIF_ID, notif)
        } catch (_: Exception) {
        }
    }

    private fun clearDevOptionsNotice() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.cancel(DEV_NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    private fun serviceIntent(action: String): Intent = Intent(context, MockLocationService::class.java).setAction(action)

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            intent.action == MockLocationService.ACTION_START_GPS
        ) {
            // Only turning GPS ON promotes the service to the foreground; every
            // other command targets the already-running service.
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "FakeGpsPlugin"
        private const val PROBE_PROVIDER = "route-spoofer-probe"
        private const val DEV_CHANNEL_ID = "route_spoofer_devopts"
        private const val DEV_NOTIF_ID = 4712
        private const val DEFAULT_SPEED = 40.0
        private const val DEFAULT_INTERVAL = 1000
    }
}
