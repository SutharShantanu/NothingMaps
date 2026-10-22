package com.shantanu.nmaps.data

import com.shantanu.nmaps.nav.EARTH_RADIUS_M
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Posted speed limits for the roads along one route, fetched once when navigation starts so the
 * sign keeps working in tunnels and dead zones and doesn't hammer Overpass every few seconds.
 */
class SpeedLimitMap(private val ways: List<Pair<List<LatLon>, Int>>) {
    val isEmpty get() = ways.isEmpty()

    /** The limit (km/h) of the tagged road nearest to [p], if one is within [maxMeters]. */
    fun at(p: LatLon, maxMeters: Double = 20.0): Int? {
        val kLat = Math.toRadians(1.0) * EARTH_RADIUS_M
        val kLon = kLat * cos(Math.toRadians(p.lat))
        var best: Int? = null
        var bestD = maxMeters
        for ((line, limit) in ways) {
            for ((a, b) in line.zipWithNext()) {
                val ax = (a.lon - p.lon) * kLon; val ay = (a.lat - p.lat) * kLat
                val bx = (b.lon - p.lon) * kLon; val by = (b.lat - p.lat) * kLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < bestD) {
                    bestD = d
                    best = limit
                }
            }
        }
        return best
    }
}
