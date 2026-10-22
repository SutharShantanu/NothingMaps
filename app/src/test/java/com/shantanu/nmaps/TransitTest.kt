package com.shantanu.nmaps

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.transit.TransitMode
import com.shantanu.nmaps.transit.Transitous
import com.shantanu.nmaps.transit.decodePolyline
import com.shantanu.nmaps.transit.steps
import com.shantanu.nmaps.transit.toRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Fixtures are real Transitous /api/v6/plan responses, trimmed to two itineraries each. */
class TransitTest {
    private fun fixture(name: String) =
        Transitous.parse(JSONObject(javaClass.getResource("/transit/$name")!!.readText()))

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun assertNear(expected: LatLon, actual: LatLon, meters: Double = 30.0) =
        assertTrue("$actual is not near $expected", distanceMeters(expected, actual) < meters)

    @Test fun polylineKnownValues() {
        // Google's documented example, precision 5.
        assertEquals(
            listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453)),
            decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5),
        )
        // The same encoding read at precision 6 is a tenth of the size.
        assertEquals(LatLon(3.85, -12.02), decodePolyline("_p~iF~ps|U", 6).single())
        assertEquals(emptyList<LatLon>(), decodePolyline(""))
    }

    @Test fun delhiRail() {
        val trips = fixture("delhi_rail.json")
        assertEquals(2, trips.size)
        val direct = trips[0]
        assertEquals(listOf(TransitMode.Walk, TransitMode.Rail, TransitMode.Walk), direct.legs.map { it.mode })
        assertEquals(ms("2026-10-23T10:36:00Z"), direct.start)
        assertEquals(ms("2026-10-23T12:33:00Z"), direct.end)
        assertEquals(7020L, direct.durationSec)
        assertEquals(0, direct.transfers)
        assertEquals(578.0 + 181.0, direct.walkMeters, 0.1)

        val train = direct.legs[1]
        assertEquals("GURGAON", train.from.name)
        assertEquals("NEW DELHI", train.to.name)
        assertEquals("54411", train.route)
        assertEquals("MEERUT CANT", train.headsign)
        assertEquals(9, train.stops)
        assertEquals(ms("2026-10-23T10:45:00Z"), train.start)
        assertNull(train.color)
        assertFalse(train.realTime)
        // Ride distance isn't in the response, so it is measured along the shape (~30 km).
        assertTrue(train.distance in 25_000.0..45_000.0)
        assertNear(train.from.point, train.points.first())
        assertNear(train.to.point, train.points.last())

        assertEquals("Start", direct.legs.first().from.name)
        assertEquals("Destination", direct.legs.last().to.name)
        assertEquals(1, trips[1].transfers)
        assertEquals(5, trips[1].legs.size)
    }

    @Test fun walkHeavyBus() {
        val trips = fixture("bengaluru_walk_bus.json")
        val t = trips[0]
        assertEquals(listOf(TransitMode.Walk, TransitMode.Bus, TransitMode.Walk), t.legs.map { it.mode })
        assertEquals(855.0 + 938.0, t.walkMeters, 0.1)
        assertEquals("293-KA", t.legs[1].route)
        assertEquals(2, t.legs[1].stops)
        assertTrue(t.legs.all { it.points.size >= 2 })
        assertEquals(listOf(TransitMode.Walk, TransitMode.Bus, TransitMode.Walk, TransitMode.Bus, TransitMode.Walk), trips[1].legs.map { it.mode })
    }

    @Test fun metroColoursAndSteps() {
        val t = fixture("berlin_sbahn_ubahn.json")[0]
        assertEquals(
            listOf(TransitMode.Walk, TransitMode.Rail, TransitMode.Walk, TransitMode.Metro, TransitMode.Walk),
            t.legs.map { it.mode },
        )
        val u8 = t.legs[3]
        assertEquals("U8", u8.route)
        assertEquals(0xFF224F86.toInt(), u8.color)
        assertEquals("Berliner Verkehrsbetriebe", u8.agency)
        assertEquals(3, u8.stops)

        assertEquals(
            listOf(
                "Walk to S+U Berlin Hauptbahnhof",
                "Take Train S5 towards S Strausberg Nord, 3 stops",
                "Get off at S+U Alexanderplatz Bhf (Berlin)",
                "Walk to S+U Alexanderplatz Bhf (Berlin)", // a 190 m change between platforms
                "Take Metro U8 towards S+U Hermannstr. (Berlin), 4 stops",
                "Get off at U Kottbusser Tor (Berlin)",
                "Walk to your destination",
                "Arrive at your destination",
            ),
            t.steps().map { it.instruction },
        )

        val route = t.toRoute()
        assertEquals(Travel.Transit, route.travel)
        assertEquals("S5 › U8", route.via)
        assertEquals(t.legs.sumOf { it.distance }, route.distance, 0.1)
        assertNear(LatLon(52.5251, 13.3694), route.points.first())
        assertNear(LatLon(52.4990, 13.4180), route.points.last(), 60.0)
        assertTrue(route.points.zipWithNext().none { (a, b) -> a == b })
    }

    @Test fun modes() {
        assertEquals(TransitMode.Metro, TransitMode.of("SUBWAY"))
        assertEquals(TransitMode.Rail, TransitMode.of("SUBURBAN"))
        assertEquals(TransitMode.Rail, TransitMode.of("HIGHSPEED_RAIL"))
        assertEquals(TransitMode.Bus, TransitMode.of("COACH"))
        assertEquals(TransitMode.Other, TransitMode.of("AIRPLANE"))
        assertNull(Transitous.parseColor(""))
        assertEquals(0xFFEB7405.toInt(), Transitous.parseColor("eb7405"))
    }
}
