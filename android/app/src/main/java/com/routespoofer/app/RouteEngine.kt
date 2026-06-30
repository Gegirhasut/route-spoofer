package com.routespoofer.app

import kotlin.math.abs
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

/** What happens when the cursor reaches a waypoint. */
enum class DwellKind(val id: String) {
    /** Pass straight through. */
    NONE("none"),

    /** Hold for [Waypoint.dwellMs], then auto-resume. */
    TIMED("timed"),

    /** Hold indefinitely until the user presses GO. */
    UNTIL_GO("until-go"),
    ;

    companion object {
        fun fromId(id: String?): DwellKind = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * A route point and its behaviour. [dwell] applies when the cursor arrives here;
 * [legSpeedKmh] overrides the global speed on the leg ENDING at this waypoint.
 */
data class Waypoint(
    val pos: LatLng,
    val dwell: DwellKind = DwellKind.NONE,
    val dwellMs: Long = 0L,
    val legSpeedKmh: Double? = null,
)

/** End-of-route behaviour. The default holds-and-broadcasts at the last point. */
enum class LoopMode(val id: String) {
    HOLD("off"),
    RESTART("restart"),
    PINGPONG("pingpong"),
    ;

    companion object {
        fun fromId(id: String?): LoopMode = entries.firstOrNull { it.id == id } ?: HOLD
    }
}

/** The live driver phase, surfaced to the status pill. */
enum class Phase { STANDING, DRIVING, WAITING_TIMED, WAITING_GO, ARRIVED }

/**
 * A snapshot of the driver after a step: the current position (which broadcasting
 * always emits), the phase, the emit speed (0 unless driving) and the data the UI
 * needs for the status pill and the lock invariant.
 */
data class DriveState(
    val lat: Double,
    val lng: Double,
    val bearing: Double,
    val seg: Int,
    val phase: Phase,
    val speedKmh: Double,
    val traveled: Double,
    val progress: Double,
    val holdRemainingMs: Long,
    val holdWaypoint: Int,
)

/**
 * Pure driving model for a waypoint polyline. It owns the geometry
 * (cumulative-haversine arc length, position/bearing interpolation) AND the
 * hold/wake state machine — but no clock and no Android: the caller passes
 * elapsed time into [step]. Broadcasting (emitting the current position) is the
 * service's job and is independent of movement; this class only moves the cursor
 * and reports where it is and what the driver is doing.
 */
class RouteEngine(initial: List<Waypoint> = emptyList()) {
    private val wps = ArrayList<Waypoint>(initial)
    private var cum = DoubleArray(1)

    /** Total route length in metres. */
    var totalDistance: Double = 0.0
        private set

    /** Arc length of the cursor from the start, in metres. */
    var traveled: Double = 0.0
        private set

    /** Current driver phase. */
    var phase: Phase = Phase.STANDING
        private set

    /** End-of-route behaviour (default [LoopMode.HOLD]). */
    var loop: LoopMode = LoopMode.HOLD

    private var holdRemainingMs: Long = 0L
    private var holdWaypoint: Int = -1
    private var forward: Boolean = true

    init {
        recompute()
    }

    val waypoints: List<Waypoint> get() = wps
    val size: Int get() = wps.size

    // ------------------------------------------------------------ simulation

    /**
     * Advance the simulation by [dtMs] using [globalSpeedKmh] as the live base
     * speed, and return the resulting [DriveState]. Driving moves the cursor (and
     * stops at dwell waypoints or the end); a timed hold counts down and
     * auto-resumes; every other phase stands still. Broadcasting is unaffected —
     * the returned position is always valid to emit.
     */
    fun step(
        dtMs: Long,
        globalSpeedKmh: Double,
    ): DriveState {
        when (phase) {
            Phase.DRIVING -> drive(dtMs, globalSpeedKmh)
            Phase.WAITING_TIMED -> {
                holdRemainingMs -= dtMs
                if (holdRemainingMs <= 0L) {
                    holdRemainingMs = 0L
                    resume()
                }
            }
            Phase.STANDING, Phase.WAITING_GO, Phase.ARRIVED -> Unit
        }
        return state(globalSpeedKmh)
    }

    /** A snapshot of the current state without advancing time. */
    fun state(globalSpeedKmh: Double): DriveState {
        val fix = sampleAt(traveled)
        val driving = phase == Phase.DRIVING
        return DriveState(
            lat = fix.lat,
            lng = fix.lng,
            bearing = fix.bearing,
            seg = fix.seg,
            phase = phase,
            speedKmh = if (driving) legSpeed(globalSpeedKmh) else 0.0,
            traveled = traveled,
            progress = if (totalDistance > 0.0) (traveled / totalDistance).coerceIn(0.0, 1.0) else 0.0,
            holdRemainingMs = if (phase == Phase.WAITING_TIMED) holdRemainingMs else 0L,
            holdWaypoint = holdWaypoint,
        )
    }

    // ------------------------------------------------------------ controls

    /** Start driving, or end ANY hold immediately (timer, until-go or arrived). */
    fun go() {
        if (wps.size < 2) {
            phase = Phase.STANDING
            return
        }
        // At the very end with nothing further to drive to: stay arrived.
        if (phase == Phase.ARRIVED && traveled >= totalDistance - EPS) return
        resume()
    }

    /** Manual hold: stand still and keep broadcasting until the user presses GO. */
    fun pause() {
        if (phase == Phase.DRIVING) {
            phase = Phase.WAITING_GO
            holdWaypoint = cursorWaypoint()
        }
    }

    /** Reset the cursor to the route start. Does NOT affect GPS emission. */
    fun stop() {
        traveled = 0.0
        phase = Phase.STANDING
        forward = true
        holdRemainingMs = 0L
        holdWaypoint = -1
    }

    private fun resume() {
        phase = Phase.DRIVING
        forward = true
        holdRemainingMs = 0L
        holdWaypoint = -1
    }

    // ------------------------------------------------------------ editing (any waypoint, anytime)

    /** Append a waypoint to the end. Never shifts the travelled arc. */
    fun appendWaypoint(wp: Waypoint) {
        wps.add(wp)
        recompute()
    }

    fun appendPoint(p: LatLng) = appendWaypoint(Waypoint(p))

    /**
     * Move any waypoint. Geometry behind the cursor may change, so the driver's
     * current WORLD position is preserved and the travelled distance re-anchored
     * onto the new route — the cursor never teleports. Editing the point being
     * driven to re-targets smoothly from the current position.
     */
    fun moveWaypoint(
        i: Int,
        p: LatLng,
    ): Boolean {
        if (i !in wps.indices) return false
        val here = worldPos()
        wps[i] = wps[i].copy(pos = p)
        recompute()
        reanchorTo(here)
        return true
    }

    /** Remove any waypoint, preserving the driver's current world position. */
    fun removeWaypoint(i: Int): Boolean {
        if (i !in wps.indices) return false
        val here = worldPos()
        wps.removeAt(i)
        if (i <= holdWaypoint) holdWaypoint = (holdWaypoint - 1).coerceAtLeast(-1)
        recompute()
        if (wps.size >= 2) {
            reanchorTo(here)
        } else {
            traveled = 0.0
            if (phase == Phase.DRIVING) phase = Phase.STANDING
        }
        return true
    }

    /** Set the dwell of any waypoint. If behind the cursor it applies on the next pass. */
    fun setWaypointDwell(
        i: Int,
        dwell: DwellKind,
        dwellMs: Long,
    ): Boolean {
        if (i !in wps.indices) return false
        wps[i] = wps[i].copy(dwell = dwell, dwellMs = dwellMs)
        return true
    }

    /** Set the speed override for the leg ending at any waypoint. */
    fun setLegSpeed(
        i: Int,
        speedKmh: Double?,
    ): Boolean {
        if (i !in wps.indices) return false
        wps[i] = wps[i].copy(legSpeedKmh = speedKmh)
        return true
    }

    /** The driver's current world position. */
    private fun worldPos(): LatLng {
        val f = sampleAt(traveled)
        return LatLng(f.lat, f.lng)
    }

    /**
     * Re-anchor [traveled] onto the (possibly changed) route while keeping the world
     * position continuous AND respecting where the cursor already was. Plain
     * nearest-point projection teleports when the edited polyline passes near the
     * cursor in more than one place (a route that bends/doubles back), often snapping
     * the cursor backward and flipping the heading (#4539). Instead we keep only the
     * segments that are essentially as close as the genuine nearest, then among those
     * pick the projection whose arc length is most continuous with [prevTraveled].
     *
     * Consequences this preserves:
     *  - edits strictly behind the cursor: the cursor's own segment is the ONLY spatial
     *    contender, so its position is reproduced exactly (no shift);
     *  - editing the active target: snaps to the nearest point on the new leg;
     *  - bearing needs no separate fix — it is read off whichever segment [traveled]
     *    lands on, so once continuity keeps the right segment the heading is correct.
     */
    private fun reanchorTo(p: LatLng) {
        if (wps.size < 2) {
            traveled = 0.0
            return
        }
        val prevTraveled = traveled

        // The genuine spatial-nearest distance. Only segments essentially this close are
        // real candidates — this keeps edits behind the cursor exact (one contender) and
        // stops a far part of the route from ever capturing the cursor.
        var nearestDist = Double.MAX_VALUE
        for (i in 0 until wps.size - 1) {
            nearestDist = minOf(nearestDist, sqrt(projectOnto(p, i).second))
        }

        var bestScore = Double.MAX_VALUE
        var bestArc = prevTraveled.coerceIn(0.0, totalDistance)
        for (i in 0 until wps.size - 1) {
            val (candArc, dist2) = projectOnto(p, i)
            // Spatial-contention gate. For a BACKWARD candidate this is exactly the
            // "only re-anchor backward if it is within tolerance of the genuine nearest"
            // rule: a doubling-back route can only pull the cursor back when that point is
            // essentially the closest.
            if (sqrt(dist2) > nearestDist + BACKWARD_TOLERANCE_M) continue
            val cont = candArc - prevTraveled
            var score = CONTINUITY_WEIGHT * cont * cont + dist2
            // Forward bias: while driving forward, penalise a backward occurrence extra so
            // a near-tie resolves forward. Skipped on the ping-pong reverse leg (`forward`
            // is false there), where moving to a lower arc length is the legitimate motion.
            if (forward && cont < -EPS) score += CONTINUITY_WEIGHT * cont * cont
            if (score < bestScore) {
                bestScore = score
                bestArc = candArc
            }
        }
        traveled = bestArc.coerceIn(0.0, totalDistance)
    }

    /**
     * Clamped projection of [p] onto segment [i]: returns the candidate arc length
     * (metres from the route start) paired with the squared planar distance, with the
     * lat/lng residual scaled to METRES so it shares units with the arc length (lets
     * [CONTINUITY_WEIGHT] and [BACKWARD_TOLERANCE_M] be expressed in plain metres).
     */
    private fun projectOnto(
        p: LatLng,
        i: Int,
    ): Pair<Double, Double> {
        val a = wps[i].pos
        val b = wps[i + 1].pos
        val abLat = b.lat - a.lat
        val abLng = b.lng - a.lng
        val len2 = abLat * abLat + abLng * abLng
        val t = if (len2 == 0.0) 0.0 else (((p.lat - a.lat) * abLat + (p.lng - a.lng) * abLng) / len2).coerceIn(0.0, 1.0)
        val mPerDegLat = EARTH_RADIUS_M * Math.toRadians(1.0)
        val mPerDegLng = mPerDegLat * cos(Math.toRadians(p.lat))
        val dLatM = (p.lat - (a.lat + abLat * t)) * mPerDegLat
        val dLngM = (p.lng - (a.lng + abLng * t)) * mPerDegLng
        val candArc = cum[i] + t * (cum[i + 1] - cum[i])
        return candArc to (dLatM * dLatM + dLngM * dLngM)
    }

    // ------------------------------------------------------------ driving internals

    private fun drive(
        dtMs: Long,
        globalSpeedKmh: Double,
    ) {
        if (wps.size < 2) {
            phase = Phase.STANDING
            return
        }
        val deltaM = (legSpeed(globalSpeedKmh) / SECONDS_PER_HOUR_OVER_KM) * (dtMs / MS_PER_S)
        if (deltaM <= 0.0) return
        if (forward) {
            val stop = nextStopIndex()
            val target = cum[stop]
            if (traveled + deltaM >= target - EPS) {
                traveled = target
                enterHoldAt(stop)
            } else {
                traveled += deltaM
            }
        } else {
            // Reverse leg of a ping-pong: run back to the start, then turn.
            if (traveled - deltaM <= EPS) {
                traveled = 0.0
                forward = true
            } else {
                traveled -= deltaM
            }
        }
    }

    private fun enterHoldAt(k: Int) {
        val last = wps.size - 1
        if (k == last) {
            when (loop) {
                LoopMode.RESTART -> {
                    traveled = 0.0
                    phase = Phase.DRIVING
                }
                LoopMode.PINGPONG -> {
                    forward = false
                    phase = Phase.DRIVING
                }
                LoopMode.HOLD -> {
                    phase = Phase.ARRIVED
                    holdWaypoint = k
                }
            }
            return
        }
        when (wps[k].dwell) {
            DwellKind.TIMED -> {
                phase = Phase.WAITING_TIMED
                holdRemainingMs = wps[k].dwellMs
                holdWaypoint = k
            }
            DwellKind.UNTIL_GO -> {
                phase = Phase.WAITING_GO
                holdWaypoint = k
            }
            DwellKind.NONE -> Unit
        }
    }

    /** The next index ahead that forces a stop: a dwell waypoint or the last one. */
    private fun nextStopIndex(): Int {
        val last = wps.size - 1
        var k = currentSeg() + 1
        while (k < last && wps[k].dwell == DwellKind.NONE) k++
        return k
    }

    private fun legSpeed(globalSpeedKmh: Double): Double {
        if (wps.size < 2) return 0.0
        val target = (currentSeg() + 1).coerceAtMost(wps.size - 1)
        return wps[target].legSpeedKmh ?: globalSpeedKmh
    }

    // The segment the cursor is on. When the cursor sits exactly on waypoint k
    // (e.g. just resumed from a dwell there) it belongs to segment k — the leg
    // LEAVING k — so the next-stop search looks ahead, not back at k.
    private fun currentSeg(): Int {
        if (wps.size < 2) return 0
        var i = 0
        while (i < cum.size - 2 && traveled >= cum[i + 1] - EPS) i++
        return i
    }

    private fun cursorWaypoint(): Int {
        for (k in cum.indices) {
            if (abs(traveled - cum[k]) <= EPS) return k
        }
        return -1
    }

    private fun recompute() {
        cum = DoubleArray(maxOf(1, wps.size))
        cum[0] = 0.0
        for (i in 1 until wps.size) {
            cum[i] = cum[i - 1] + haversine(wps[i - 1].pos, wps[i].pos)
        }
        totalDistance = cum[cum.size - 1]
    }

    // ------------------------------------------------------------ geometry

    /** Position and heading at arc length [meters] along the route. */
    fun sampleAt(meters: Double): Fix {
        if (wps.isEmpty()) return Fix(0.0, 0.0, 0.0, 0)
        if (wps.size == 1) return Fix(wps[0].pos.lat, wps[0].pos.lng, 0.0, 0)
        val s = maxOf(0.0, minOf(meters, totalDistance))
        var i = 0
        while (i < cum.size - 2 && s > cum[i + 1]) i++
        val a = wps[i].pos
        val b = wps[i + 1].pos
        val segLen = (cum[i + 1] - cum[i]).let { if (it == 0.0) 1.0 else it }
        val t = (s - cum[i]) / segLen
        return Fix(
            lat = a.lat + (b.lat - a.lat) * t,
            lng = a.lng + (b.lng - a.lng) * t,
            bearing = bearing(a, b),
            seg = i,
        )
    }

    companion object {
        /** Mean Earth radius in metres. */
        const val EARTH_RADIUS_M = 6371000.0

        private const val EPS = 1e-6
        private const val MS_PER_S = 1000.0
        private const val SECONDS_PER_HOUR_OVER_KM = 3.6 // (km/h) / 3.6 = m/s

        /**
         * Reanchor tunable — weight of arc-length continuity vs spatial closeness when a
         * mid-run edit re-projects the cursor (both terms are metres², so 1.0 weights them
         * equally). Increase to keep the cursor stickier to its current arc (more resistant
         * to teleporting onto a far part of the route); decrease toward a pure
         * nearest-point snap. See [reanchorTo].
         */
        private const val CONTINUITY_WEIGHT = 1.0

        /**
         * Reanchor tunable — how far (metres) a candidate projection may be beyond the
         * genuine nearest point and still be considered. This is also the "only re-anchor
         * BACKWARD if it is essentially the nearest" tolerance (#4539): a route that doubles
         * back near the cursor may pull the cursor backward only when that backward point is
         * within this slack of being the closest. Increase to tolerate looser/backward
         * re-anchors; decrease to demand the re-anchor be almost exactly the nearest point.
         */
        private const val BACKWARD_TOLERANCE_M = 5.0

        /** Build an engine from bare points (no dwell / overrides). */
        fun fromPoints(points: List<LatLng>): RouteEngine = RouteEngine(points.map { Waypoint(it) })

        /** Convenience for tests. */
        fun of(vararg points: LatLng): RouteEngine = fromPoints(points.toList())

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
