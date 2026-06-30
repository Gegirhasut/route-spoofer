package com.routespoofer.app

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * Owns the OS mock-location plumbing. TWO layers run together so every kind of
 * consumer follows the simulated route (#4529):
 *
 *  - LocationManager test providers (GPS + NETWORK) — for AOSP / no-GMS devices
 *    (Huawei etc.) and anything reading LocationManager directly.
 *  - Fused Location Provider (FLP) mock mode — what Google Play Services feeds to
 *    fused consumers like Google Maps and Yandex Maps. Without it FLP keeps running
 *    its own providers and blends in the device's REAL fixes, so the simulated
 *    position flips between fake and real and never moves smoothly. setMockMode(true)
 *    makes FLP report ONLY the locations we push.
 *
 * The layers are independent: if Play Services is absent the FLP calls fail (logged)
 * and the LocationManager layer still mocks; if the app is not the selected mock app
 * the LocationManager registration fails (logged) and we do not pretend to be ready.
 */
class MockLocationInjector(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var flpMockEngaged = false

    /**
     * Register + enable the LocationManager test providers and put FLP into mock
     * mode. Re-asserts registration and enabled state on every start so a transient
     * OS/location reset that disabled them is recovered, and only reports ready when
     * the registration actually succeeded (a failed start can retry next time).
     * Returns whether the LocationManager layer registered, i.e. the app is the
     * selected mock-location app.
     */
    fun setup(): Boolean {
        var lmOk = true
        for (name in PROVIDERS) {
            if (!addProvider(name)) lmOk = false
        }
        if (!lmOk) {
            Log.w(TAG, "Test providers not fully registered — is Route Spoofer the selected mock location app?")
        }
        engageFlpMockMode()
        return lmOk
    }

    /** Disable + remove the test providers and leave FLP mock mode. Idempotent. */
    fun teardown() {
        for (name in PROVIDERS) {
            setProviderEnabled(name, false)
            removeProvider(name)
        }
        disengageFlpMockMode()
    }

    /** Push [fix] (ground speed [speedMs], m/s) into the LM providers AND FLP. */
    fun inject(
        fix: Fix,
        speedMs: Double,
    ) {
        for (name in PROVIDERS) {
            pushTestProviderLocation(name, buildLocation(name, fix, speedMs))
        }
        pushFusedMockLocation(buildLocation(LocationManager.GPS_PROVIDER, fix, speedMs))
    }

    // ------------------------------------------------------------- location object

    private fun buildLocation(
        provider: String,
        fix: Fix,
        speedMs: Double,
    ): Location =
        Location(provider).apply {
            latitude = fix.lat
            longitude = fix.lng
            altitude = 0.0
            accuracy = ACCURACY_M
            bearing = fix.bearing.toFloat()
            speed = speedMs.toFloat()
            // Fresh, monotonically-advancing timestamps on every push — FLP rejects
            // stale / backwards fixes, which is part of what froze the marker.
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 1f
                speedAccuracyMetersPerSecond = 1f
                verticalAccuracyMeters = VERTICAL_ACCURACY_M
            }
            // Flag as mock across API levels so FLP and strict consumers / ROMs accept it.
            LocationCompat.setMock(this, true)
        }

    // ----------------------------------------------------------- LocationManager

    private fun addProvider(name: String): Boolean {
        removeProvider(name) // clear any stale registration first
        val added =
            try {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    name,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "addTestProvider($name) denied — app not selected as the mock location app", e)
                false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "addTestProvider($name) failed — provider unavailable on this device", e)
                false
            }
        // Always (re-)assert enabled: a previous run or an OS reset may have left it off.
        return added && setProviderEnabled(name, true)
    }

    private fun setProviderEnabled(
        name: String,
        enabled: Boolean,
    ): Boolean =
        try {
            lm.setTestProviderEnabled(name, enabled)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "setTestProviderEnabled($name=$enabled) denied", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setTestProviderEnabled($name=$enabled) failed — provider not registered", e)
            false
        }

    private fun removeProvider(name: String) {
        try {
            lm.removeTestProvider(name)
        } catch (e: IllegalArgumentException) {
            // Provider was not registered — expected on first run / cleanup no-op.
            Log.d(TAG, "removeTestProvider($name): nothing to remove", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeTestProvider($name) denied", e)
        }
    }

    private fun pushTestProviderLocation(
        name: String,
        loc: Location,
    ) {
        try {
            lm.setTestProviderLocation(name, loc)
        } catch (e: SecurityException) {
            Log.w(TAG, "setTestProviderLocation($name) denied", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setTestProviderLocation($name) rejected the location", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setTestProviderLocation($name) — provider not enabled", e)
        }
    }

    // ------------------------------------------------------------- Fused (GMS) layer

    private fun engageFlpMockMode() {
        fusedClient.setMockMode(true)
            .addOnSuccessListener {
                flpMockEngaged = true
                Log.i(TAG, "FLP mock mode engaged — fused consumers now follow the route")
            }
            .addOnFailureListener { e ->
                flpMockEngaged = false
                Log.w(TAG, "FLP setMockMode(true) failed — fused consumers may see real location", e)
            }
    }

    private fun disengageFlpMockMode() {
        flpMockEngaged = false
        // Always attempt, regardless of whether engage succeeded, so the device is
        // never left stuck in mock mode. If the process dies first, GMS resets it.
        fusedClient.setMockMode(false)
            .addOnFailureListener { e -> Log.w(TAG, "FLP setMockMode(false) failed", e) }
    }

    private fun pushFusedMockLocation(loc: Location) {
        if (!flpMockEngaged) return
        fusedClient.setMockLocation(loc)
            .addOnFailureListener { e -> Log.w(TAG, "FLP setMockLocation failed", e) }
    }

    private companion object {
        const val TAG = "MockLocationInjector"
        const val ACCURACY_M = 4f
        const val VERTICAL_ACCURACY_M = 3f
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
