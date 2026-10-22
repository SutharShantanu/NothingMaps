package com.shantanu.nmaps.timeline

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.pathLength

/** One stored location sample. */
data class HistoryPoint(val time: Long, val lat: Double, val lon: Double, val accuracy: Float, val speed: Float = 0f) {
    val latLon get() = LatLon(lat, lon)
}

enum class TripMode(val label: String) { Walking("Walking"), Cycling("Cycling"), Driving("Driving"), Moving("Moving") }

sealed interface Segment {
    val start: Long
    val end: Long
}

/** Time spent in one place. */
data class Visit(override val start: Long, override val end: Long, val center: LatLon) : Segment

/** Movement between places. */
data class Trip(
    override val start: Long,
    override val end: Long,
    val points: List<LatLon>,
    val distance: Double,
    val mode: TripMode,
) : Segment

/**
 * Splits a day of location samples into visits and trips, the way Google Maps Timeline presents them.
 * A visit is a run of samples that stays within [stayRadius] for at least [minStayMs];
 * everything between visits is a trip, classified by its typical speed.
 */
object TimelineBuilder {

    fun segments(
        raw: List<HistoryPoint>,
        stayRadius: Double = 120.0,
        minStayMs: Long = 5 * 60_000L,
        maxAccuracy: Float = 200f,
    ): List<Segment> {
        val pts = raw.filter { it.accuracy <= maxAccuracy }.sortedBy { it.time }
        if (pts.isEmpty()) return emptyList()

        val out = mutableListOf<Segment>()
        var tripPoints = mutableListOf<HistoryPoint>()
        var i = 0
        while (i < pts.size) {
            // Grow a cluster from i while samples stay near its running centre.
            var j = i
            var cLat = pts[i].lat
            var cLon = pts[i].lon
            while (j + 1 < pts.size && distanceMeters(LatLon(cLat, cLon), pts[j + 1].latLon) <= stayRadius) {
                j++
                val n = (j - i + 1).toDouble()
                cLat += (pts[j].lat - cLat) / n
                cLon += (pts[j].lon - cLon) / n
            }
            if (pts[j].time - pts[i].time >= minStayMs) {
                tripPoints.add(pts[i])
                trip(tripPoints)?.let { out += it }
                out += Visit(pts[i].time, pts[j].time, LatLon(cLat, cLon))
                tripPoints = mutableListOf(pts[j])
                i = j + 1
            } else {
                tripPoints.add(pts[i])
                i++
            }
        }
        trip(tripPoints)?.let { out += it }
        return mergeVisits(out, stayRadius)
    }

    private fun trip(points: List<HistoryPoint>): Trip? {
        if (points.size < 2) return null
        val line = points.map { it.latLon }
        val distance = pathLength(line)
        if (distance < 150) return null
        return Trip(points.first().time, points.last().time, line, distance, mode(points, distance))
    }

    /**
     * Upper-quartile speed between samples, so stops at lights don't make a drive look like a ride;
     * falls back to the average when samples are sparse.
     */
    internal fun mode(points: List<HistoryPoint>, distance: Double): TripMode {
        val speeds = points.zipWithNext().mapNotNull { (a, b) ->
            val dt = (b.time - a.time) / 1000.0
            if (dt <= 0) null else distanceMeters(a.latLon, b.latLon) / dt
        }.sorted()
        val duration = (points.last().time - points.first().time) / 1000.0
        val typical = when {
            speeds.size >= 3 -> speeds[(speeds.size * 3) / 4]
            duration > 0 -> distance / duration
            else -> return TripMode.Moving
        }
        return when {
            typical < 2.2 -> TripMode.Walking   // < 8 km/h
            typical < 6.5 -> TripMode.Cycling   // < 23 km/h
            else -> TripMode.Driving
        }
    }

    /** Two visits to the same spot with only a short hop between them are one visit. */
    private fun mergeVisits(segments: List<Segment>, stayRadius: Double): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segments) {
            val lastVisit = out.lastOrNull { it is Visit } as? Visit
            if (s is Visit && lastVisit != null && distanceMeters(lastVisit.center, s.center) < stayRadius * 1.5) {
                val between = out.subList(out.indexOf(lastVisit) + 1, out.size)
                if (between.all { it is Trip && it.distance < 400 }) {
                    between.clear()
                    out[out.lastIndex] = lastVisit.copy(end = s.end)
                    continue
                }
            }
            out += s
        }
        return out
    }
}

/** A month at a glance: distance by travel mode, places, and where the time went. */
data class MonthSummary(
    val days: Int,
    val distanceByMode: Map<TripMode, Double>,
    val visits: Int,
    /** Place centres with total time spent, longest first. */
    val topPlaces: List<Pair<LatLon, Long>>,
)

object TimelineInsights {
    fun month(segmentsByDay: List<List<Segment>>, sameRadius: Double = 150.0): MonthSummary {
        val trips = segmentsByDay.flatten().filterIsInstance<Trip>()
        val visits = segmentsByDay.flatten().filterIsInstance<Visit>()
        val places = mutableListOf<Pair<LatLon, Long>>()
        for (v in visits) {
            val i = places.indexOfFirst { distanceMeters(it.first, v.center) < sameRadius }
            val dur = v.end - v.start
            if (i >= 0) places[i] = places[i].first to places[i].second + dur else places += v.center to dur
        }
        return MonthSummary(
            days = segmentsByDay.count { it.isNotEmpty() },
            distanceByMode = trips.groupBy { it.mode }.mapValues { (_, t) -> t.sumOf { it.distance } },
            visits = visits.size,
            topPlaces = places.sortedByDescending { it.second }.take(5),
        )
    }

    /**
     * Guesses home (where nights are spent) and work (where weekday daytime goes) from visits,
     * the way Google suggests them. Needs a few days of history to say anything.
     */
    fun homeAndWork(visits: List<Visit>, hourOf: (Long) -> Int, isWeekday: (Long) -> Boolean, sameRadius: Double = 150.0): Pair<LatLon?, LatLon?> {
        fun bucket(filter: (Long) -> Boolean): LatLon? {
            val places = mutableListOf<Pair<LatLon, Long>>()
            for (v in visits) {
                // Count the visit's time in hourly steps that match the filter.
                var t = v.start
                var hit = 0L
                while (t < v.end) {
                    if (filter(t)) hit += minOf(3_600_000L, v.end - t)
                    t += 3_600_000L
                }
                if (hit == 0L) continue
                val i = places.indexOfFirst { distanceMeters(it.first, v.center) < sameRadius }
                if (i >= 0) places[i] = places[i].first to places[i].second + hit else places += v.center to hit
            }
            return places.maxByOrNull { it.second }?.takeIf { it.second >= 6 * 3_600_000L }?.first
        }
        val home = bucket { hourOf(it) >= 22 || hourOf(it) < 6 }
        val work = bucket { isWeekday(it) && hourOf(it) in 10..16 }?.takeIf { w -> home == null || distanceMeters(w, home) > 300 }
        return home to work
    }
}
