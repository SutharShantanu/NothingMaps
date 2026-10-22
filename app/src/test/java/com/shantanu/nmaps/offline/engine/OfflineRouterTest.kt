package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.pathLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRouterTest {
    private val router = OfflineRouter(Fixtures.source)
    private val metro = LatLon(28.446412, 77.100103)
    private val paras = LatLon(28.450968, 77.087551)
    private val pizzaHut = LatLon(28.421858, 77.091)

    private fun Route.check(from: LatLon, to: LatLon) {
        val straight = distanceMeters(from, to)
        assertTrue("$distance vs $straight", distance in straight..straight * 2.5)
        assertEquals(pathLength(points), distance, 1.0)
        assertTrue(distanceMeters(points.first(), from) < 300 && distanceMeters(points.last(), to) < 300)
        assertEquals("depart", steps.first().type)
        assertEquals("arrive", steps.last().type)
        assertEquals(listOf(steps.lastIndex), legEnds)
        assertEquals(distance, steps.sumOf { it.distance }, 1.0)
        assertTrue(duration > 0)
    }

    @Test fun driveAndWalkOnRealTiles() {
        var t = System.nanoTime()
        val drive = router.route(listOf(metro, paras), Travel.Drive)
        println("Drive route incl. graph build: ${(System.nanoTime() - t) / 1_000_000} ms, ${drive.distance.toInt()} m, ${drive.steps.size} steps, via ${drive.via}")
        drive.steps.forEach { println("  ${it.instruction} (${it.distance.toInt()} m)") }
        drive.check(metro, paras)
        // Urban driving averages somewhere between 10 and 60 km/h.
        assertTrue(drive.distance / drive.duration in 10 / 3.6..60 / 3.6)
        assertTrue(drive.steps.any { it.type == "turn" })
        assertTrue(drive.via.isNotEmpty())

        t = System.nanoTime()
        val walk = router.route(listOf(metro, paras), Travel.Walk)
        println("Walk route (cached graph): ${(System.nanoTime() - t) / 1_000_000} ms, ${walk.distance.toInt()} m")
        walk.check(metro, paras)
        // Walking may snap to a different (closer) footpath, so it isn't necessarily shorter here;
        // the synthetic tests cover walking against one-way streets.
        assertEquals(walk.distance / (4.8 / 3.6), walk.duration, walk.duration * 0.01 + 1)

        val cycle = router.route(listOf(metro, paras), Travel.Cycle)
        cycle.check(metro, paras)
    }

    @Test fun crossesTilesWithStops() {
        val t = System.nanoTime()
        val r = router.route(listOf(metro, paras, pizzaHut), Travel.Drive)
        println("Two-leg drive: ${(System.nanoTime() - t) / 1_000_000} ms, ${r.distance.toInt()} m")
        assertEquals(2, r.legEnds.size)
        assertEquals("Arrive at stop 1", r.steps[r.legEnds[0]].instruction)
        assertEquals(r.steps.lastIndex, r.legEnds[1])
        assertEquals("depart", r.steps[r.legEnds[0] + 1].type)
        val direct = distanceMeters(metro, paras) + distanceMeters(paras, pizzaHut)
        assertTrue(r.distance in direct..direct * 2.5)
    }

    @Test fun matchesOsrmAcrossTheGolfCourseRoadMedian() {
        // OSRM on the same data: 2684 m, departing on Golf Course Road and U-turning through the
        // median at 28.438016, 77.103468. The start is beside a private lane, which must not win the snap.
        val a = LatLon(28.4420, 77.1030)
        val b = LatLon(28.4466, 77.0945)
        val there = router.route(listOf(a, b), Travel.Drive)
        assertEquals(2684.0, there.distance, 2684 * 0.1)
        val uturn = there.steps.single { it.modifier == "uturn" }
        assertTrue(distanceMeters(uturn.at, LatLon(28.438016, 77.103468)) < 30)
        // And back: OSRM 1716 m.
        assertEquals(1716.0, router.route(listOf(b, a), Travel.Drive).distance, 1716 * 0.1)
    }

    @Test fun outsideCoverage() {
        val delhi = LatLon(28.6139, 77.2090)
        val e1 = assertThrows(OfflineRouteException::class.java) { router.route(listOf(delhi, metro), Travel.Drive) }
        assertEquals("This start isn't in a downloaded area", e1.message)
        val e2 = assertThrows(OfflineRouteException::class.java) { router.route(listOf(metro, delhi), Travel.Walk) }
        assertEquals("No road near the destination in your downloaded maps", e2.message)
        val empty = OfflineRouter(MemoryTileSource(emptyMap()))
        assertThrows(OfflineRouteException::class.java) { empty.route(listOf(metro, paras), Travel.Drive) }
    }
}
