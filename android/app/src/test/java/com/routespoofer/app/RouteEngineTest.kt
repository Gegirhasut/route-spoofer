package com.routespoofer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RouteEngine] — the pure movement math extracted from the
 * service. No Android dependencies, so these run on `./gradlew test`.
 *
 * Reference distances/bearings were computed independently from the haversine
 * formula: one degree of longitude at the equator is ~111194.9266 m.
 */
class RouteEngineTest {
    private val eps = 1e-6
    private val degMeters = 111194.92664455874 // haversine length of 1° at the equator

    // ------------------------------------------------------------ distance

    @Test
    fun totalDistance_emptyRoute_isZero() {
        assertEquals(0.0, RouteEngine(emptyList()).totalDistance, eps)
    }

    @Test
    fun totalDistance_singleWaypoint_isZero() {
        assertEquals(0.0, RouteEngine(listOf(LatLng(55.75, 37.62))).totalDistance, eps)
    }

    @Test
    fun totalDistance_oneDegreeEast_matchesHaversine() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        assertEquals(degMeters, engine.totalDistance, 1e-3)
    }

    @Test
    fun totalDistance_isSumOfSegments() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0)))
        assertEquals(2 * degMeters, engine.totalDistance, 1e-3)
    }

    // ------------------------------------------------------------ interpolation

    @Test
    fun sampleAt_start_returnsFirstWaypoint() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val fix = engine.sampleAt(0.0)
        assertEquals(0.0, fix.lat, eps)
        assertEquals(0.0, fix.lng, eps)
        assertEquals(0, fix.seg)
    }

    @Test
    fun sampleAt_end_returnsLastWaypoint() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val fix = engine.sampleAt(engine.totalDistance)
        assertEquals(0.0, fix.lat, eps)
        assertEquals(1.0, fix.lng, eps)
    }

    @Test
    fun sampleAt_midpoint_interpolatesLinearly() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val fix = engine.sampleAt(engine.totalDistance / 2.0)
        assertEquals(0.0, fix.lat, eps)
        assertEquals(0.5, fix.lng, eps)
        assertEquals(90.0, fix.bearing, eps) // due east
    }

    @Test
    fun sampleAt_secondSegment_reportsSegIndexAndBearing() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0)))
        val fix = engine.sampleAt(engine.totalDistance / 2.0 + 10.0) // just past the first vertex
        assertEquals(1, fix.seg)
        assertEquals(0.0, fix.bearing, eps) // second segment heads due north
        assertEquals(1.0, fix.lng, eps)
    }

    @Test
    fun sampleAt_beyondTotal_clampsToEnd() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val fix = engine.sampleAt(engine.totalDistance + 50_000.0)
        assertEquals(1.0, fix.lng, eps)
    }

    @Test
    fun sampleAt_emptyRoute_isOrigin() {
        val fix = RouteEngine(emptyList()).sampleAt(123.0)
        assertEquals(0.0, fix.lat, eps)
        assertEquals(0.0, fix.lng, eps)
        assertEquals(0, fix.seg)
    }

    @Test
    fun sampleAt_singleWaypoint_isStationary() {
        val engine = RouteEngine(listOf(LatLng(12.34, 56.78)))
        val a = engine.sampleAt(0.0)
        val b = engine.sampleAt(9999.0)
        assertEquals(12.34, a.lat, eps)
        assertEquals(56.78, a.lng, eps)
        assertEquals(a.lat, b.lat, eps)
        assertEquals(a.lng, b.lng, eps)
    }

    @Test
    fun sampleAt_zeroLengthSegment_doesNotDivideByZero() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val fix = engine.sampleAt(0.0)
        assertEquals(0.0, fix.lat, eps)
        assertEquals(0.0, fix.lng, eps)
    }

    // ------------------------------------------------------------ bearing

    @Test
    fun bearing_cardinalDirections() {
        val o = LatLng(0.0, 0.0)
        assertEquals(90.0, RouteEngine.bearing(o, LatLng(0.0, 1.0)), eps) // east
        assertEquals(0.0, RouteEngine.bearing(o, LatLng(1.0, 0.0)), eps) // north
        assertEquals(270.0, RouteEngine.bearing(o, LatLng(0.0, -1.0)), eps) // west
        assertEquals(180.0, RouteEngine.bearing(o, LatLng(-1.0, 0.0)), eps) // south
    }

    // ------------------------------------------------------------ loop: OFF

    @Test
    fun advance_off_stopsAtEnd() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val total = engine.totalDistance
        val adv = engine.advance(total - 10.0, 1, 20.0, LoopMode.OFF)
        assertTrue(adv.atEnd)
        assertEquals(total, adv.traveled, eps)
        assertEquals(1, adv.dir)
    }

    @Test
    fun advance_off_beforeEnd_movesForward() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val adv = engine.advance(0.0, 1, 100.0, LoopMode.OFF)
        assertEquals(100.0, adv.traveled, eps)
        assertEquals(1, adv.dir)
        assertTrue(!adv.atEnd)
    }

    // ------------------------------------------------------------ loop: RESTART

    @Test
    fun advance_restart_wrapsToStart() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val total = engine.totalDistance
        val adv = engine.advance(total - 10.0, 1, 20.0, LoopMode.RESTART)
        assertTrue(!adv.atEnd)
        assertEquals(10.0, adv.traveled, eps) // overshoot (10) carried past the start
        assertEquals(1, adv.dir)
    }

    // ------------------------------------------------------------ loop: PING-PONG

    @Test
    fun advance_pingpong_reflectsAndFlipsAtEnd() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val total = engine.totalDistance
        val adv = engine.advance(total - 10.0, 1, 20.0, LoopMode.PINGPONG)
        assertTrue(!adv.atEnd)
        assertEquals(total - 10.0, adv.traveled, eps) // reflected back from the end
        assertEquals(-1, adv.dir) // now travelling backwards
    }

    @Test
    fun advance_pingpong_reflectsAndFlipsAtStart() {
        val engine = RouteEngine(listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)))
        val adv = engine.advance(10.0, -1, 20.0, LoopMode.PINGPONG)
        assertTrue(!adv.atEnd)
        assertEquals(10.0, adv.traveled, eps) // reflected forward from the start
        assertEquals(1, adv.dir) // now travelling forwards
    }

    // ------------------------------------------------------------ loop: edge cases

    @Test
    fun advance_emptyRoute_returnsInputUnchanged() {
        val adv = RouteEngine(emptyList()).advance(5.0, 1, 999.0, LoopMode.RESTART)
        assertEquals(5.0, adv.traveled, eps)
        assertEquals(1, adv.dir)
        assertTrue(!adv.atEnd)
    }

    @Test
    fun advance_singleWaypoint_returnsInputUnchanged() {
        val adv = RouteEngine(listOf(LatLng(1.0, 2.0))).advance(7.0, -1, 999.0, LoopMode.PINGPONG)
        assertEquals(7.0, adv.traveled, eps)
        assertEquals(-1, adv.dir)
        assertTrue(!adv.atEnd)
    }

    @Test
    fun loopMode_fromId_mapsKnownAndDefaultsToOff() {
        assertEquals(LoopMode.OFF, LoopMode.fromId("off"))
        assertEquals(LoopMode.RESTART, LoopMode.fromId("restart"))
        assertEquals(LoopMode.PINGPONG, LoopMode.fromId("pingpong"))
        assertEquals(LoopMode.OFF, LoopMode.fromId(null))
        assertEquals(LoopMode.OFF, LoopMode.fromId("bogus"))
    }
}
