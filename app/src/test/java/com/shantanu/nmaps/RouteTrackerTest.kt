package com.shantanu.nmaps

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Step
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.nav.RouteTracker
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatPace
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTrackerTest {
    // An L: 1 km north, then 1 km east, with a right turn at the corner.
    private val corner = LatLon(28.009, 77.0)
    private val end = LatLon(28.009, 77.0102)
    private val route = Route(
        Travel.Drive,
        listOf(LatLon(28.0, 77.0), corner, end),
        2000.0, 200.0,
        listOf(
            Step("Head north", "depart", null, 1000.0, LatLon(28.0, 77.0)),
            Step("Turn right", "turn", "right", 1000.0, corner),
            Step("Arrive", "arrive", null, 0.0, end),
        ),
    )

    @Test fun progressAlongTheLine() {
        val t = RouteTracker(route)
        val p = t.update(LatLon(28.0045, 77.00002))
        assertEquals(1, p.stepIndex)
        assertEquals(500.0, p.toManeuver, 15.0)
        assertEquals(1500.0, p.remaining, 30.0)
        assertEquals(2.0, p.offRoute, 1.0)

        val after = t.update(LatLon(28.009, 77.005))
        assertEquals(2, after.stepIndex)
        assertEquals(510.0, after.remaining, 30.0)
    }

    @Test fun offRouteDistance() {
        val p = RouteTracker(route).update(LatLon(28.0045, 77.001))
        assertEquals(98.0, p.offRoute, 5.0)
    }

    @Test fun formatting() {
        assertEquals("250 m", formatDistance(247.0, imperial = false).toString())
        assertEquals("1.5 km", formatDistance(1520.0, imperial = false).toString().replace(',', '.'))
        assertEquals("500 ft", formatDistance(152.4, imperial = true).toString())
        assertEquals("5:00 /km", formatPace(300_000, 1000.0, imperial = false).toString())
    }
}
