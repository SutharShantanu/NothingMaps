package com.shantanu.nmaps.nav

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.UnitPrefs
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6_371_000.0
private const val METERS_PER_MILE = 1609.344
private const val FEET_PER_METER = 3.28084

fun distanceMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/** Initial compass bearing from [a] to [b], 0..360. */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val la = Math.toRadians(a.lat)
    val lb = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lb)
    val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun pathLength(points: List<LatLon>): Double =
    points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

/** A display value split into the number (drawn in dots) and its unit. */
data class Measure(val value: String, val unit: String) {
    override fun toString() = if (unit.isEmpty()) value else "$value $unit"
}

fun formatDistance(meters: Double, imperial: Boolean = UnitPrefs.imperial): Measure {
    if (imperial) {
        val feet = meters * FEET_PER_METER
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.1 -> Measure("${(feet / 10).roundToInt() * 10}", "ft")
            miles < 10 -> Measure(String.format(Locale.getDefault(), "%.1f", miles), "mi")
            else -> Measure("${miles.roundToInt()}", "mi")
        }
    }
    return when {
        meters < 100 -> Measure("${(meters / 5).roundToInt() * 5}", "m")
        meters < 1000 -> Measure("${(meters / 10).roundToInt() * 10}", "m")
        meters < 10_000 -> Measure(String.format(Locale.getDefault(), "%.1f", meters / 1000), "km")
        else -> Measure("${(meters / 1000).roundToInt()}", "km")
    }
}

fun formatSpeed(metersPerSecond: Float, imperial: Boolean = UnitPrefs.imperial): Measure =
    if (imperial) Measure("${(metersPerSecond * 2.23694f).roundToInt()}", "mph")
    else Measure("${(metersPerSecond * 3.6f).roundToInt()}", "km/h")

fun formatElevation(meters: Double, imperial: Boolean = UnitPrefs.imperial): Measure =
    if (imperial) Measure("${(meters * FEET_PER_METER).roundToInt()}", "ft") else Measure("${meters.roundToInt()}", "m")

fun formatDuration(seconds: Double): List<Measure> {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) {
        listOf(Measure("$minutes", "min"))
    } else {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) listOf(Measure("$h", "hr")) else listOf(Measure("$h", "hr"), Measure("$m", "min"))
    }
}

/** Stopwatch time: "4:05" or "1:02:33". */
fun formatClock(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Pace per kilometre (or mile), "5:42"; a dash until there's enough distance to say. */
fun formatPace(movingMs: Long, meters: Double, imperial: Boolean = UnitPrefs.imperial): Measure {
    val unit = if (imperial) "/mi" else "/km"
    if (meters < 50) return Measure("–:––", unit)
    val per = if (imperial) METERS_PER_MILE else 1000.0
    val secPer = (movingMs / 1000.0) / (meters / per)
    if (secPer > 3600) return Measure("–:––", unit)
    return Measure("%d:%02d".format(secPer.toInt() / 60, secPer.toInt() % 60), unit)
}

fun spokenDistance(meters: Double): String {
    val d = formatDistance(meters)
    return when (d.unit) {
        "m" -> "${d.value} metres"
        "km" -> "${d.value} kilometres"
        "ft" -> "${d.value} feet"
        else -> "${d.value} miles"
    }
}
