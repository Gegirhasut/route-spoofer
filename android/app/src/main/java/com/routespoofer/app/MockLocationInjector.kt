package com.routespoofer.app

import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/**
 * Owns the OS mock-location plumbing: registering and enabling the GPS + NETWORK
 * test providers and pushing a fully-populated [Location] into them. Pulled out
 * of [MockLocationService] so the lifecycle/notification code stays focused. The
 * behaviour — providers used, location fields set, exceptions swallowed — is
 * unchanged from the original inline implementation.
 */
class MockLocationInjector(private val lm: LocationManager) {
    private var providersReady = false

    /** Register and enable the test providers. Idempotent. */
    fun setup() {
        if (providersReady) return
        addProvider(LocationManager.GPS_PROVIDER)
        addProvider(LocationManager.NETWORK_PROVIDER)
        providersReady = true
    }

    /** Disable and remove the test providers. Idempotent. */
    fun teardown() {
        if (!providersReady) return
        for (name in PROVIDERS) {
            try {
                lm.setTestProviderEnabled(name, false)
            } catch (_: Exception) {
            }
            try {
                lm.removeTestProvider(name)
            } catch (_: Exception) {
            }
        }
        providersReady = false
    }

    /** Push [fix] (with ground speed [speedMs], m/s) into both test providers. */
    fun inject(
        fix: Fix,
        speedMs: Double,
    ) {
        for (name in PROVIDERS) {
            val loc =
                Location(name).apply {
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
            try {
                lm.setTestProviderLocation(name, loc)
            } catch (_: Exception) {
            }
        }
    }

    private fun addProvider(name: String) {
        try {
            try {
                lm.removeTestProvider(name)
            } catch (_: Exception) {
            }
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
            lm.setTestProviderEnabled(name, true)
        } catch (_: SecurityException) {
            // Not selected as the mock-location app — nothing we can do here.
        } catch (_: Exception) {
            // Provider may not exist on this device (e.g. NETWORK) — ignore.
        }
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
