package com.routespoofer.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A geographic point in decimal degrees. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * A sampled position along the route: coordinates, heading in degrees and the
 * index of the segment the point falls on.
 */
data class Fix(val lat: Double, val lng: Double, val bearing: Double, val seg: Int)

/** Loop behaviour applied when playback reaches a route end. */
enum class LoopMode(val id: String) {
    OFF("off"),
    RESTART("restart"),
    PINGPONG("pingpong"),
    ;

    companion object {
        /**
         * Maps the JS/intent string to a mode, defaulting to [OFF] (the legacy
         * `else` branch behaviour) for null or unknown values.
         */
        fun fromId(id: String?): LoopMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * Result of advancing playback one step: the new travelled distance, the new
 * direction (`+1` forward / `-1` backward) and whether the route just ended
 * (only ever `true` for [LoopMode.OFF]).
 */
data class Advance(val traveled: Double, val dir: Int, val atEnd: Boolean)

/**
 * Pure movement math for a waypoint polyline: cumulative-haversine arc length,
 * position/bearing interpolation and loop semantics. It has no Android
 * dependencies, so it is unit-testable on the JVM. The geometry mirrors what
 * previously lived inside `MockLocationService` byte-for-byte.
 */
class RouteEngine(val waypoints: List<LatLng>) {
    /** Cumulative distance in metres from the first waypoint to each waypoint. */
    private val cum: DoubleArray = DoubleArray(maxOf(1, waypoints.size))

    /** Total route length in metres. */
    val totalDistance: Double

    init {
        cum[0] = 0.0
        for (i in 1 until waypoints.size) {
            cum[i] = cum[i - 1] + haversine(waypoints[i - 1], waypoints[i])
        }
        totalDistance = cum[cum.size - 1]
    }

    /** Position and heading at arc length [meters] along the route. */
    fun sampleAt(meters: Double): Fix {
        if (waypoints.isEmpty()) return Fix(0.0, 0.0, 0.0, 0)
        if (waypoints.size == 1) return Fix(waypoints[0].lat, waypoints[0].lng, 0.0, 0)
        val s = maxOf(0.0, minOf(meters, totalDistance))
        var i = 0
        while (i < cum.size - 2 && s > cum[i + 1]) i++
        val a = waypoints[i]
        val b = waypoints[i + 1]
        val segLen = (cum[i + 1] - cum[i]).let { if (it == 0.0) 1.0 else it }
        val t = (s - cum[i]) / segLen
        return Fix(
            lat = a.lat + (b.lat - a.lat) * t,
            lng = a.lng + (b.lng - a.lng) * t,
            bearing = bearing(a, b),
            seg = i,
        )
    }

    /**
     * Advance [traveled] (metres) by [deltaMeters] in direction [dir], applying
     * [loop] at the ends. A route with fewer than two waypoints has nothing to
     * traverse, so the input is returned unchanged. Mirrors the wrapping that
     * `MockLocationService.tick()` performed.
     */
    fun advance(
        traveled: Double,
        dir: Int,
        deltaMeters: Double,
        loop: LoopMode,
    ): Advance {
        if (waypoints.size <= 1) return Advance(traveled, dir, false)
        var t = traveled + deltaMeters * dir
        var d = dir
        if (t >= totalDistance) {
            when (loop) {
                LoopMode.RESTART -> t -= totalDistance
                LoopMode.PINGPONG -> {
                    t = totalDistance - (t - totalDistance)
                    d = -1
                }
                LoopMode.OFF -> return Advance(totalDistance, d, true)
            }
        } else if (t <= 0 && d == -1) {
            when (loop) {
                LoopMode.PINGPONG -> {
                    t = -t
                    d = 1
                }
                LoopMode.RESTART -> t = totalDistance
                LoopMode.OFF -> t = 0.0
            }
        }
        return Advance(t, d, false)
    }

    companion object {
        /** Mean Earth radius in metres (matches the original service constant). */
        const val EARTH_RADIUS_M = 6371000.0

        /** Great-circle distance between two points, in metres. */
        fun haversine(
            a: LatLng,
            b: LatLng,
        ): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val s =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
                    sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_RADIUS_M * Math.asin(sqrt(s))
        }

        /** Initial bearing from [a] to [b], in degrees within `[0, 360)`. */
        fun bearing(
            a: LatLng,
            b: LatLng,
        ): Double {
            val y = sin(Math.toRadians(b.lng - a.lng)) * cos(Math.toRadians(b.lat))
            val x =
                cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) -
                    sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
                    cos(Math.toRadians(b.lng - a.lng))
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}
