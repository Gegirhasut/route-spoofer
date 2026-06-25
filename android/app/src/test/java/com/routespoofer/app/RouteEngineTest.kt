package com.routespoofer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RouteEngine] — the pure geometry + hold/wake driving model.
 * No Android dependencies, so these run on `./gradlew test`.
 *
 * Reference: one degree of longitude at the equator is ~111194.9266 m. Driving
 * tests use a speed of 3600 km/h (= 1000 m/s) so distance == seconds, keeping the
 * arithmetic obvious.
 */
class RouteEngineTest {
    private val eps = 1e-6
    private val deg = 111194.92664455874 // haversine length of 1° at the equator
    private val fast = 3600.0 // km/h -> 1000 m/s
    private val bigStepMs = 200_000L // at `fast`, 200 km — long enough to cross a 1° leg

    private fun pt(
        lat: Double,
        lng: Double,
    ) = LatLng(lat, lng)

    // ============================================================ geometry

    @Test fun totalDistance_emptyRoute_isZero() = assertEquals(0.0, RouteEngine().totalDistance, eps)

    @Test fun totalDistance_singleWaypoint_isZero() = assertEquals(0.0, RouteEngine.of(pt(55.75, 37.62)).totalDistance, eps)

    @Test fun totalDistance_oneDegreeEast_matchesHaversine() =
        assertEquals(
            deg,
            RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0)).totalDistance,
            1e-3,
        )

    @Test fun totalDistance_isSumOfSegments() =
        assertEquals(2 * deg, RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0), pt(1.0, 1.0)).totalDistance, 1e-3)

    @Test fun sampleAt_start_returnsFirstWaypoint() {
        val f = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0)).sampleAt(0.0)
        assertEquals(0.0, f.lat, eps)
        assertEquals(0.0, f.lng, eps)
        assertEquals(0, f.seg)
    }

    @Test fun sampleAt_end_returnsLastWaypoint() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        val f = e.sampleAt(e.totalDistance)
        assertEquals(1.0, f.lng, eps)
    }

    @Test fun sampleAt_midpoint_interpolatesLinearly() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        val f = e.sampleAt(e.totalDistance / 2.0)
        assertEquals(0.5, f.lng, eps)
        assertEquals(90.0, f.bearing, eps)
    }

    @Test fun sampleAt_secondSegment_reportsSegIndex() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0), pt(1.0, 1.0))
        val f = e.sampleAt(e.totalDistance / 2.0 + 10.0)
        assertEquals(1, f.seg)
        assertEquals(0.0, f.bearing, eps)
    }

    @Test fun sampleAt_beyondTotal_clampsToEnd() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        assertEquals(1.0, e.sampleAt(e.totalDistance + 50_000.0).lng, eps)
    }

    @Test fun sampleAt_zeroLengthSegment_doesNotDivideByZero() {
        val f = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 0.0), pt(0.0, 1.0)).sampleAt(0.0)
        assertEquals(0.0, f.lat, eps)
        assertEquals(0.0, f.lng, eps)
    }

    @Test fun bearing_cardinalDirections() {
        val o = pt(0.0, 0.0)
        assertEquals(90.0, RouteEngine.bearing(o, pt(0.0, 1.0)), eps)
        assertEquals(0.0, RouteEngine.bearing(o, pt(1.0, 0.0)), eps)
        assertEquals(270.0, RouteEngine.bearing(o, pt(0.0, -1.0)), eps)
        assertEquals(180.0, RouteEngine.bearing(o, pt(-1.0, 0.0)), eps)
    }

    // ============================================================ broadcast vs movement

    @Test fun fresh_route_isStandingNotDriving() {
        val s = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0)).state(fast)
        assertEquals(Phase.STANDING, s.phase)
        assertEquals(0.0, s.speedKmh, eps)
    }

    @Test fun singleWaypoint_standsAndBroadcastsAtThatPoint() {
        val e = RouteEngine.of(pt(5.0, 6.0))
        e.go() // cannot drive with one point
        val s = e.step(bigStepMs, fast)
        assertEquals(Phase.STANDING, s.phase)
        assertEquals(5.0, s.lat, eps)
        assertEquals(6.0, s.lng, eps)
        assertEquals(0.0, s.speedKmh, eps)
    }

    @Test fun go_thenStep_drives() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        val s = e.step(1000L, fast) // 1000 m
        assertEquals(Phase.DRIVING, s.phase)
        assertEquals(1000.0, s.traveled, 1e-3)
        assertEquals(fast, s.speedKmh, eps)
    }

    @Test fun stop_resetsCursorToStart_keepsStanding() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        e.step(1000L, fast)
        e.stop()
        val s = e.state(fast)
        assertEquals(0.0, s.traveled, eps)
        assertEquals(Phase.STANDING, s.phase)
        assertEquals(0.0, s.lng, eps)
    }

    // ============================================================ dwell / hold / wake

    private fun dwellRoute(
        dwell: DwellKind,
        ms: Long,
    ) = RouteEngine(
        listOf(
            Waypoint(pt(0.0, 0.0)),
            Waypoint(pt(0.0, 1.0), dwell = dwell, dwellMs = ms),
            Waypoint(pt(0.0, 2.0)),
        ),
    )

    @Test fun hold_enteredAtUntilGoDwellWaypoint() {
        val e = dwellRoute(DwellKind.UNTIL_GO, 0L)
        e.go()
        val s = e.step(bigStepMs, fast)
        assertEquals(Phase.WAITING_GO, s.phase)
        assertEquals(1, s.holdWaypoint)
        assertEquals(deg, s.traveled, 1e-2) // parked exactly on waypoint B
    }

    @Test fun timedDwell_holdsThenAutoResumes() {
        val e = dwellRoute(DwellKind.TIMED, 120_000L)
        e.go()
        assertEquals(Phase.WAITING_TIMED, e.step(bigStepMs, fast).phase)
        assertEquals(Phase.WAITING_TIMED, e.step(60_000L, fast).phase) // 60s of 120s
        assertEquals(60_000L, e.state(fast).holdRemainingMs)
        val s = e.step(60_000L, fast) // timer elapses -> resume
        assertEquals(Phase.DRIVING, s.phase)
    }

    @Test fun go_endsTimedDwellEarly() {
        val e = dwellRoute(DwellKind.TIMED, 600_000L)
        e.go()
        e.step(bigStepMs, fast) // now WAITING_TIMED with 10 min left
        e.go() // GO ends it immediately
        assertEquals(Phase.DRIVING, e.state(fast).phase)
    }

    @Test fun untilGo_neverAutoResumes() {
        val e = dwellRoute(DwellKind.UNTIL_GO, 0L)
        e.go()
        e.step(bigStepMs, fast)
        repeat(5) { e.step(bigStepMs, fast) }
        assertEquals(Phase.WAITING_GO, e.state(fast).phase)
        e.go()
        assertEquals(Phase.DRIVING, e.step(1000L, fast).phase)
    }

    @Test fun manualPause_isUntilGoHold() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        e.step(1000L, fast) // driving mid-leg
        e.pause()
        assertEquals(Phase.WAITING_GO, e.state(fast).phase)
        e.step(bigStepMs, fast) // does not move while held
        assertEquals(Phase.WAITING_GO, e.state(fast).phase)
        assertEquals(1000.0, e.state(fast).traveled, 1e-3)
        e.go()
        assertEquals(Phase.DRIVING, e.step(1000L, fast).phase)
    }

    // ============================================================ end of route

    @Test fun endOfRoute_holdsAndKeepsBroadcasting_noStop() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        val arrived = e.step(bigStepMs, fast)
        assertEquals(Phase.ARRIVED, arrived.phase)
        assertEquals(e.totalDistance, arrived.traveled, 1e-3)
        assertEquals(1.0, arrived.lng, eps)
        // further steps keep the same position and stay ARRIVED (still emitting)
        val later = e.step(bigStepMs, fast)
        assertEquals(Phase.ARRIVED, later.phase)
        assertEquals(1.0, later.lng, eps)
        assertEquals(0.0, later.speedKmh, eps)
    }

    @Test fun appendWhileArrived_drivesToItOnGo() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        e.step(bigStepMs, fast) // ARRIVED at end
        e.appendPoint(pt(0.0, 2.0)) // extend route; must NOT auto-start
        assertEquals(Phase.ARRIVED, e.state(fast).phase)
        e.go() // now there is somewhere to drive
        assertEquals(Phase.DRIVING, e.step(1000L, fast).phase)
    }

    // ============================================================ speed: per-leg + live

    @Test fun perLegOverride_beatsGlobal() {
        val base = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        base.go()
        val withGlobal = base.step(1000L, 1000.0).traveled // 1000/3.6 m

        val over =
            RouteEngine(
                listOf(Waypoint(pt(0.0, 0.0)), Waypoint(pt(0.0, 1.0), legSpeedKmh = fast)),
            )
        over.go()
        val withOverride = over.step(1000L, 1000.0).traveled // override 3600 -> 1000 m

        assertEquals(1000.0 / 3.6, withGlobal, 1e-3)
        assertEquals(1000.0, withOverride, 1e-3)
    }

    @Test fun liveGlobalSpeedChange_appliesMidLeg() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0))
        e.go()
        e.step(1000L, 1000.0) // +277.78 m
        val s = e.step(1000L, fast) // +1000 m at the new live speed
        assertEquals(1000.0 / 3.6 + 1000.0, s.traveled, 1e-3)
        assertEquals(fast, s.speedKmh, eps)
    }

    // ============================================================ live editing + lock invariant

    @Test fun appendBeyondCursor_doesNotShiftTravelled() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0), pt(0.0, 2.0))
        e.go()
        e.step(1000L, fast) // traveled = 1000 m on leg 0
        val before = e.state(fast)
        val totalBefore = e.totalDistance
        e.appendPoint(pt(1.0, 2.0))
        val after = e.state(fast)
        assertEquals(before.traveled, after.traveled, eps) // arc length unchanged
        assertEquals(before.lat, after.lat, eps)
        assertEquals(before.lng, after.lng, eps)
        assertTrue(e.totalDistance > totalBefore) // route got longer
    }

    @Test fun lockInvariant_passedAndCurrentTargetAreImmutable() {
        val e =
            RouteEngine(
                (0..3).map { Waypoint(pt(0.0, it.toDouble())) }, // 0,1,2,3
            )
        e.go()
        e.step(1000L, fast) // driving leg 0 -> 1; locked through index 1
        assertEquals(1, e.lockedThrough())
        assertFalse("passed start is locked", e.moveWaypoint(0, pt(9.0, 9.0)))
        assertFalse("current target is locked", e.moveWaypoint(1, pt(9.0, 9.0)))
        assertFalse("cannot remove the target", e.removeWaypoint(1))
        assertTrue("future waypoint is editable", e.moveWaypoint(2, pt(0.5, 2.0)))
        assertTrue("future dwell is editable", e.setWaypointDwell(3, DwellKind.TIMED, 1000L))
        assertTrue("future leg speed is editable", e.setLegSpeed(3, 50.0))
    }

    @Test fun standingAtStart_nextWaypointStillEditable() {
        val e = RouteEngine.of(pt(0.0, 0.0), pt(0.0, 1.0), pt(0.0, 2.0))
        // not driving yet: only the start (index 0) is locked
        assertEquals(0, e.lockedThrough())
        assertTrue(e.moveWaypoint(1, pt(0.0, 0.5)))
    }
}
