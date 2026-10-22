package com.shantanu.nmaps.nav

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Follows the user along a route: snaps each fix onto the polyline, works out which
 * manoeuvre is next, and how far away it and the destination are.
 */
class RouteTracker(val route: Route) {

    data class Progress(
        /** Index into [Route.steps] of the next manoeuvre. */
        val stepIndex: Int,
        val toManeuver: Double,
        val remaining: Double,
        /** Distance from the fix to the route line. */
        val offRoute: Double,
    )

    private val pts = route.points
    private val cumulative = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + distanceMeters(pts[i - 1], pts[i])
    }
    private val total = cumulative.lastOrNull() ?: 0.0

    /** Distance along the line of each step's manoeuvre point, matched in order. */
    private val stepAlong: DoubleArray = run {
        var from = 0
        DoubleArray(route.steps.size) { i ->
            val at = route.steps[i].at
            var best = from
            var bestD = Double.MAX_VALUE
            for (j in from until pts.size) {
                val d = distanceMeters(pts[j], at)
                if (d < bestD) {
                    bestD = d
                    best = j
                }
            }
            from = best
            cumulative.getOrElse(best) { 0.0 }
        }
    }

    private var lastSegment = 0

    fun update(p: LatLon): Progress {
        if (pts.size < 2) return Progress(route.steps.lastIndex.coerceAtLeast(0), 0.0, 0.0, distanceMeters(p, pts.firstOrNull() ?: p))

        // Look near where we were last; fall back to the whole line if that misses.
        var snap = nearest(p, (lastSegment - 3).coerceAtLeast(0), (lastSegment + 80).coerceAtMost(pts.size - 2))
        if (snap.distance > 40) {
            val full = nearest(p, 0, pts.size - 2)
            if (full.distance < snap.distance) snap = full
        }
        lastSegment = snap.segment

        val along = cumulative[snap.segment] + snap.t * (cumulative[snap.segment + 1] - cumulative[snap.segment])
        // Step 0 is "depart"; the next manoeuvre is the first one still ahead of us.
        val next = (1 until stepAlong.size).firstOrNull { stepAlong[it] > along + 3 } ?: route.steps.lastIndex
        return Progress(
            stepIndex = next.coerceAtLeast(0),
            toManeuver = (stepAlong.getOrElse(next) { total } - along).coerceAtLeast(0.0),
            remaining = (total - along).coerceAtLeast(0.0),
            offRoute = snap.distance,
        )
    }

    private data class Snap(val segment: Int, val t: Double, val distance: Double)

    private fun nearest(p: LatLon, from: Int, to: Int): Snap {
        // Local flat projection in metres around p — accurate enough at street scale.
        val kLat = Math.toRadians(1.0) * EARTH_RADIUS_M
        val kLon = kLat * cos(Math.toRadians(p.lat))
        fun x(a: LatLon) = (a.lon - p.lon) * kLon
        fun y(a: LatLon) = (a.lat - p.lat) * kLat

        var best = Snap(from, 0.0, Double.MAX_VALUE)
        for (i in from..to) {
            val ax = x(pts[i]); val ay = y(pts[i])
            val bx = x(pts[i + 1]); val by = y(pts[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best.distance) best = Snap(i, t, d)
        }
        return best
    }
}
