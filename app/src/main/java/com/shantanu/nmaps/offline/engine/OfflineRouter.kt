package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.Instructions
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Step
import com.shantanu.nmaps.nav.bearingDegrees
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.data.Travel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OfflineRouteException(message: String) : Exception(message)

/**
 * Turn-by-turn routing on the roads of the downloaded vector tiles, for when there is no network.
 * Produces the same [Route] shape as the online router so navigation works unchanged.
 */
class OfflineRouter(source: TileSource) {
    private val cache = TileCache(source)
    private var graph: Pair<Set<TileId>, RoadGraph>? = null

    @Synchronized
    fun invalidate() {
        graph = null
        cache.clear()
    }

    /** Route through [points] in order (start, any stops, destination); one leg per pair. */
    fun route(points: List<LatLon>, travel: Travel, avoid: Set<String> = emptySet()): Route {
        require(points.size >= 2)
        val available = cache.source.tiles(GLOBAL_ZOOM).toSet()
        points.forEachIndexed { i, p ->
            if (tileOf(p) !in available) throw OfflineRouteException(missing(i, points.size))
        }
        val profile = Profile(travel, avoid)
        var lastError: OfflineRouteException? = null
        for (marginM in MARGINS_M) {
            val graph = graphFor(points, marginM, available)
            val snaps = points.mapIndexed { i, p -> snap(graph, profile, p) ?: throw OfflineRouteException(missing(i, points.size)) }
            val legs = snaps.zipWithNext().map { (a, b) -> shortest(graph, profile, a, b) }
            if (legs.all { it != null }) return assemble(graph, profile, legs.filterNotNull())
            lastError = OfflineRouteException("No offline route between these places")
        }
        throw lastError!!
    }

    private fun missing(i: Int, n: Int) = when (i) {
        0 -> "This start isn't in a downloaded area"
        n - 1 -> "No road near the destination in your downloaded maps"
        else -> "No road near stop $i in your downloaded maps"
    }

    @Synchronized
    private fun graphFor(points: List<LatLon>, marginM: Double, available: Set<TileId>): RoadGraph {
        val g = points.map(::latLonToGlobal)
        val margin = marginM / metersPerUnit(g.minOf { it.second }) / GLOBAL_EXTENT
        val tx0 = floor(g.minOf { it.first } / GLOBAL_EXTENT - margin).toInt()
        val tx1 = floor(g.maxOf { it.first } / GLOBAL_EXTENT + margin).toInt()
        val ty0 = floor(g.minOf { it.second } / GLOBAL_EXTENT - margin).toInt()
        val ty1 = floor(g.maxOf { it.second } / GLOBAL_EXTENT + margin).toInt()
        val wanted = available.filter { it.x in tx0..tx1 && it.y in ty0..ty1 }.toSet()
        graph?.let { (ids, graph) -> if (ids.containsAll(wanted)) return graph }
        val built = RoadGraph(wanted.mapNotNull { id -> cache.get(id)?.let { id to it } })
        graph = wanted to built
        return built
    }

    /** A point on edge [edge], [t] of the way from its first to its second node. */
    private class Snap(val edge: Int, val t: Double, val x: Double, val y: Double)

    /**
     * The nearest point on a road the mode may use, within [SNAP_M]. Private roads count as
     * [RESTRICTED_SNAP_M] further away, so a start beside a gated lane picks the public road (as OSRM
     * does) unless the lane is much closer.
     */
    private fun snap(g: RoadGraph, profile: Profile, p: LatLon): Snap? {
        val (px, py) = latLonToGlobal(p)
        val mpu = metersPerUnit(py)
        val radius = SNAP_M / mpu
        val extra = RESTRICTED_SNAP_M / mpu
        var best: Snap? = null
        var bestD = radius + extra
        for (e in 0 until g.edgeCount) {
            val a = g.from(e)
            val b = g.to(e)
            val ax = g.x(a).toDouble(); val ay = g.y(a).toDouble()
            val bx = g.x(b).toDouble(); val by = g.y(b).toDouble()
            if (px < min(ax, bx) - bestD || px > max(ax, bx) + bestD || py < min(ay, by) - bestD || py > max(ay, by) + bestD) continue
            val way = g.ways[g.way(e)]
            if (profile.speed(way) <= 0) continue
            val (t, d) = pointSegment(px, py, ax, ay, bx, by)
            val score = if (profile.restricted(way)) d + extra else d
            if (d <= radius && score < bestD) {
                bestD = score
                best = Snap(e, t, ax + (bx - ax) * t, ay + (by - ay) * t)
            }
        }
        return best
    }

    /** A leg as the visited positions in global units and the edge used to reach each one from the previous. */
    private class Leg(val xs: DoubleArray, val ys: DoubleArray, val edges: IntArray, val seconds: DoubleArray, val nodes: IntArray)

    /** Seconds to go along [e] from its first node to its second (forward) or back; null if not allowed. */
    private fun cost(g: RoadGraph, profile: Profile, e: Int, forward: Boolean, fraction: Double = 1.0): Pair<Double, Double>? {
        val way = g.ways[g.way(e)]
        val speed = profile.speed(way)
        val dir = profile.oneway(way)
        if (speed <= 0 || (forward && dir < 0) || (!forward && dir > 0)) return null
        val time = g.length(e) * fraction / speed
        return time * profile.penalty(way) to time
    }

    private fun shortest(g: RoadGraph, profile: Profile, from: Snap, to: Snap): Leg? {
        val n = g.nodeCount
        val s = n
        val t = n + 1
        val weight = DoubleArray(n + 2) { Double.POSITIVE_INFINITY }
        val time = DoubleArray(n + 2)
        val prev = IntArray(n + 2) { -1 }
        val prevEdge = IntArray(n + 2) { -1 }
        val done = BooleanArray(n + 2)
        val heap = Heap()
        val mpu = metersPerUnit(max(from.y, to.y)) * 0.98
        fun h(x: Double, y: Double) = hypot(x - to.x, y - to.y) * mpu / profile.maxSpeed
        fun relax(u: Int, v: Int, e: Int, c: Pair<Double, Double>?, vx: Double, vy: Double) {
            if (c == null || done[v]) return
            val w = weight[u] + c.first
            if (w < weight[v]) {
                weight[v] = w
                time[v] = time[u] + c.second
                prev[v] = u
                prevEdge[v] = e
                heap.push(w + h(vx, vy), v)
            }
        }
        // Ways into the destination: from either end of its edge, or straight along the start's edge.
        val c = g.from(to.edge)
        val d = g.to(to.edge)
        val intoC = cost(g, profile, to.edge, true, to.t)
        val intoD = cost(g, profile, to.edge, false, 1 - to.t)

        weight[s] = 0.0
        val a = g.from(from.edge)
        val b = g.to(from.edge)
        relax(s, a, from.edge, cost(g, profile, from.edge, false, from.t), g.x(a).toDouble(), g.y(a).toDouble())
        relax(s, b, from.edge, cost(g, profile, from.edge, true, 1 - from.t), g.x(b).toDouble(), g.y(b).toDouble())
        if (from.edge == to.edge) {
            relax(s, t, from.edge, cost(g, profile, from.edge, to.t >= from.t, abs(to.t - from.t)), to.x, to.y)
        }
        done[s] = true

        while (heap.size > 0) {
            val u = heap.pop()
            if (done[u]) continue
            done[u] = true
            if (u == t) break
            if (u == c) relax(u, t, to.edge, intoC, to.x, to.y)
            if (u == d) relax(u, t, to.edge, intoD, to.x, to.y)
            for (k in g.adjStart[u] until g.adjStart[u + 1]) {
                val e = g.adjEdge[k]
                val v = g.other(e, u)
                relax(u, v, e, cost(g, profile, e, g.from(e) == u), g.x(v).toDouble(), g.y(v).toDouble())
            }
        }
        if (!done[t]) return null

        val order = ArrayList<Int>()
        var v = t
        while (v != -1) { order += v; v = prev[v] }
        order.reverse()
        fun px(v: Int) = when (v) { s -> from.x; t -> to.x; else -> g.x(v).toDouble() }
        fun py(v: Int) = when (v) { s -> from.y; t -> to.y; else -> g.y(v).toDouble() }
        return Leg(
            DoubleArray(order.size) { px(order[it]) },
            DoubleArray(order.size) { py(order[it]) },
            IntArray(order.size) { prevEdge[order[it]] },
            DoubleArray(order.size) { time[order[it]] },
            IntArray(order.size) { if (order[it] >= n) -1 else order[it] },
        )
    }

    private fun assemble(g: RoadGraph, profile: Profile, legs: List<Leg>): Route {
        val points = ArrayList<LatLon>()
        val steps = ArrayList<Step>()
        val legEnds = ArrayList<Int>()
        val nameLength = HashMap<String, Double>()
        var distance = 0.0
        var duration = 0.0
        legs.forEachIndexed { li, leg ->
            val pts = leg.xs.indices.map { globalToLatLon(leg.xs[it], leg.ys[it]) }
            val names = Array(pts.size) { i ->
                if (i == 0) "" else g.names.lookup(leg.xs[i - 1], leg.ys[i - 1], leg.xs[i], leg.ys[i])
            }
            val along = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                along[i] = along[i - 1] + distanceMeters(pts[i - 1], pts[i])
                if (names[i].isNotEmpty()) nameLength.merge(names[i], along[i] - along[i - 1], Double::plus)
            }
            val maneuvers = maneuvers(g, profile, leg, pts, names, along)
            maneuvers.forEachIndexed { k, m ->
                val next = if (k + 1 < maneuvers.size) along[maneuvers[k + 1].index] else along.last()
                val text = Instructions.text(m.type, m.modifier, m.name, null)
                steps += Step(text, m.type, m.modifier, next - along[m.index], pts[m.index])
            }
            val last = li == legs.lastIndex
            steps += Step(if (last) Instructions.text("arrive", null, "", null) else "Arrive at stop ${li + 1}", "arrive", null, 0.0, pts.last())
            legEnds += steps.lastIndex
            points += if (points.isNotEmpty() && points.last() == pts.first()) pts.drop(1) else pts
            distance += along.last()
            duration += leg.seconds.last()
        }
        val via = nameLength.maxByOrNull { it.value }?.key.orEmpty()
        return Route(profile.travel, points, distance, duration, steps, via, legEnds = legEnds)
    }

    private class Maneuver(val index: Int, val type: String, val delta: Double, val name: String, val inBearing: Double = 0.0) {
        val modifier: String?
            get() = if (type == "depart") null else modifierOf(delta)
    }

    /** Depart, then a turn wherever the heading changes sharply at a junction, or the road's name changes. */
    private fun maneuvers(g: RoadGraph, profile: Profile, leg: Leg, pts: List<LatLon>, names: Array<String>, along: DoubleArray): List<Maneuver> {
        fun nameAhead(i: Int): String {
            var j = i + 1
            while (j < pts.size && along[j] - along[i] < NAME_LOOKAHEAD_M) {
                if (names[j].isNotEmpty()) return names[j]
                j++
            }
            return ""
        }
        val out = arrayListOf(Maneuver(0, "depart", 0.0, nameAhead(0)))
        // Headings are measured over a short stretch, but never back past the previous maneuver.
        fun pointAt(i: Int, offset: Double): LatLon {
            var j = i
            if (offset < 0) while (j > out.last().index && along[i] - along[j] < -offset) j--
            else while (j < pts.lastIndex && along[j] - along[i] < offset) j++
            return pts[j]
        }
        var road = out[0].name
        for (i in 1 until pts.lastIndex) {
            val node = leg.nodes[i]
            if (node < 0 || !junction(g, profile, node)) continue
            val inBearing = bearingDegrees(pointAt(i, -BEARING_M), pts[i])
            val outBearing = bearingDegrees(pts[i], pointAt(i, BEARING_M))
            val delta = ((outBearing - inBearing + 540) % 360) - 180
            val name = nameAhead(i)
            val prev = out.last()
            when {
                abs(delta) > TURN_DEG && prev.type == "turn" && prev.delta * delta > 0 && along[i] - along[prev.index] < MERGE_M -> {
                    // Two quick turns the same way, e.g. through a median U-turn: say it as one,
                    // measured from the heading before the first to the heading after the second.
                    val total = ((outBearing - prev.inBearing + 540) % 360) - 180
                    out[out.lastIndex] = Maneuver(prev.index, "turn", total, name, prev.inBearing)
                }
                abs(delta) > TURN_DEG -> out += Maneuver(i, "turn", delta, name, inBearing)
                name.isNotEmpty() && road.isNotEmpty() && name != road -> out += Maneuver(i, "new name", 0.0, name)
                name.isNotEmpty() && road.isEmpty() -> { road = name; continue }
                else -> continue
            }
            road = out.last().name
        }
        return out
    }

    /** Three or more ways usable by this mode meet at [node]. */
    private fun junction(g: RoadGraph, profile: Profile, node: Int): Boolean {
        var count = 0
        for (k in g.adjStart[node] until g.adjStart[node + 1]) {
            if (profile.speed(g.ways[g.way(g.adjEdge[k])]) > 0 && ++count >= 3) return true
        }
        return false
    }

    private class Heap {
        private var keys = DoubleArray(256)
        private var values = IntArray(256)
        var size = 0
            private set

        fun push(key: Double, value: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); values = values.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= key) break
                keys[i] = keys[p]; values[i] = values[p]; i = p
            }
            keys[i] = key; values[i] = value
        }

        fun pop(): Int {
            val top = values[0]
            val key = keys[--size]
            val value = values[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && keys[c + 1] < keys[c]) c++
                if (keys[c] >= key) break
                keys[i] = keys[c]; values[i] = values[c]; i = c
            }
            keys[i] = key; values[i] = value
            return top
        }
    }

    private companion object {
        /** Search area around the points; widened once if the roads inside don't connect them. */
        val MARGINS_M = listOf(3_000.0, 15_000.0)
        const val SNAP_M = 300.0
        const val RESTRICTED_SNAP_M = 100.0
        const val TURN_DEG = 35.0
        const val MERGE_M = 40.0
        const val BEARING_M = 15.0
        const val NAME_LOOKAHEAD_M = 40.0

        fun tileOf(p: LatLon): TileId {
            val (x, y) = latLonToGlobal(p)
            return TileId(GLOBAL_ZOOM, (x / GLOBAL_EXTENT).toInt(), (y / GLOBAL_EXTENT).toInt())
        }

        /** OSRM-style turn modifier for a heading change in degrees, positive to the right. */
        fun modifierOf(delta: Double): String {
            val a = abs(delta)
            val side = if (delta > 0) "right" else "left"
            return when {
                a < 15 -> "straight"
                a < 60 -> "slight $side"
                a < 135 -> side
                a < 165 -> "sharp $side"
                else -> "uturn"
            }
        }
    }
}
