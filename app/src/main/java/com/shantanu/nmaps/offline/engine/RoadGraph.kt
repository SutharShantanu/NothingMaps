package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Travel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** OpenMapTiles `transportation` attributes that matter for routing; shared by many edges. */
internal data class Way(
    val cls: String,
    val subclass: String? = null,
    val oneway: Int = 0,
    val ramp: Boolean = false,
    val access: String? = null,
    val bicycle: String? = null,
    val foot: String? = null,
    val service: String? = null,
    val brunnel: String? = null,
    val toll: Boolean = false,
)

/**
 * Per-travel-mode rules: speeds in m/s, 0 where the mode may not go. [avoid] may hold "motorway"
 * (motorways and their ramps), "toll" and "ferry".
 */
internal class Profile(val travel: Travel, private val avoid: Set<String> = emptySet()) {
    fun speed(w: Way): Double {
        if (w.cls in avoid || (w.toll && "toll" in avoid)) return 0.0
        if (w.cls == "ferry") return FERRY_KMH / 3.6
        val kmh = when (travel) {
            Travel.Drive -> when {
                w.cls == "path" || w.cls == "track" || w.cls == "pier" || w.subclass in NON_CAR -> 0.0
                w.service == "parking_aisle" || w.service == "driveway" -> 10.0
                else -> DRIVE_KMH[w.cls]?.let { if (w.ramp) it * 0.6 else it } ?: 0.0
            }
            Travel.Cycle -> when {
                w.cls == "motorway" || w.cls == "trunk" || w.bicycle == "no" || w.cls !in ROUTABLE -> 0.0
                w.subclass == "steps" -> 3.0
                w.subclass == "footway" || w.subclass == "pedestrian" -> 8.0
                w.cls == "track" -> 12.0
                else -> 16.0
            }
            Travel.Walk, Travel.Transit -> if (w.cls == "motorway" || w.cls == "trunk" || w.foot == "no" || w.cls !in ROUTABLE) 0.0 else 4.8
        }
        return kmh / 3.6
    }

    /** Travel time is multiplied by this to steer away from roads that are private or unpleasant. */
    fun penalty(w: Way) = if (restricted(w)) 4.0 else 1.0

    fun restricted(w: Way) = w.access == "no" || w.access == "private"

    /** Direction the mode must respect: 1 along the geometry, -1 against it, 0 either way. */
    fun oneway(w: Way) = when (travel) {
        Travel.Drive -> w.oneway
        Travel.Cycle -> if (w.cls == "path") 0 else w.oneway
        Travel.Walk, Travel.Transit -> 0
    }

    val maxSpeed = when (travel) {
        Travel.Drive -> DRIVE_KMH.values.max()
        Travel.Cycle -> 16.0
        Travel.Walk, Travel.Transit -> 4.8
    } / 3.6

    companion object {
        val DRIVE_KMH = mapOf(
            "motorway" to 80.0, "trunk" to 65.0, "primary" to 50.0, "secondary" to 40.0,
            "tertiary" to 35.0, "minor" to 25.0, "service" to 15.0,
        )
        const val FERRY_KMH = 10.0
        val ROUTABLE = DRIVE_KMH.keys + setOf("track", "path", "pier", "ferry")
        val NON_CAR = setOf("pedestrian", "footway", "cycleway", "steps", "path", "bridleway", "corridor", "platform")
    }
}

/**
 * The road network of a set of z14 tiles. Vertices live on one global integer grid (tile x * 4096 + px),
 * so the same OSM vertex coincides across tiles. Each tile only contributes the part of its lines
 * inside its own square; where a line leaves the tile, both neighbours cut it at the shared edge and
 * those cut points are merged with a small tolerance. Lines that cross without a shared vertex
 * (bridges, tunnels) stay unconnected.
 */
internal class RoadGraph(tiles: List<Pair<TileId, MvtTile>>) {
    private val xs = IntBuf(1024)
    private val ys = IntBuf(1024)
    private val nodeAt = LongIntMap()
    private val ea = IntBuf(1024)
    private val eb = IntBuf(1024)
    private val eway = IntBuf(1024)
    private var elen = DoubleArray(1024)
    val ways = ArrayList<Way>()
    private val wayIndex = HashMap<Way, Int>()
    val names = NameIndex()

    val nodeCount get() = xs.size
    val edgeCount get() = ea.size
    lateinit var adjStart: IntArray
    lateinit var adjEdge: IntArray

    init {
        val edgeKeys = LongIntMap()
        for ((id, tile) in tiles) {
            if (id.z != GLOBAL_ZOOM) continue
            val ox = id.x.toDouble() * GLOBAL_EXTENT
            val oy = id.y.toDouble() * GLOBAL_EXTENT
            tile.layer("transportation_name")?.let { names.add(it, ox, oy) }
            val layer = tile.layer("transportation") ?: continue
            val scale = GLOBAL_EXTENT.toDouble() / layer.extent
            for (f in layer.features) {
                if (f.type != GeomType.Line) continue
                val cls = f.string("class") ?: continue
                if (cls !in Profile.ROUTABLE) continue
                val way = Way(
                    cls, f.string("subclass"), f.long("oneway")?.toInt()?.coerceIn(-1, 1) ?: 0, f.long("ramp") == 1L,
                    f.string("access"), f.string("bicycle"), f.string("foot"), f.string("service"),
                    f.string("brunnel"), f.long("toll") == 1L,
                )
                val w = wayIndex.getOrPut(way) { ways += way; ways.lastIndex }
                for (part in f.geometry) {
                    for (i in 2 until part.size step 2) {
                        val x1 = ox + part[i - 2] * scale
                        val y1 = oy + part[i - 1] * scale
                        val x2 = ox + part[i] * scale
                        val y2 = oy + part[i + 1] * scale
                        val inside = x1 >= ox && x2 >= ox && y1 >= oy && y2 >= oy &&
                            x1 <= ox + GLOBAL_EXTENT && x2 <= ox + GLOBAL_EXTENT && y1 <= oy + GLOBAL_EXTENT && y2 <= oy + GLOBAL_EXTENT
                        val (t0, t1) = if (inside) 0.0 to 1.0 else clip(x1, y1, x2, y2, ox, oy, ox + GLOBAL_EXTENT, oy + GLOBAL_EXTENT) ?: continue
                        val a = node(x1 + (x2 - x1) * t0, y1 + (y2 - y1) * t0, t0 > 0)
                        val b = node(x1 + (x2 - x1) * t1, y1 + (y2 - y1) * t1, t1 < 1)
                        if (a == b) continue
                        val ek = min(a, b).toLong() shl 32 or max(a, b).toLong()
                        if (edgeKeys[ek] >= 0) continue
                        edgeKeys.put(ek, 1)
                        addEdge(a, b, w)
                    }
                }
            }
        }
        repairJunctions()
        buildAdjacency()
    }

    fun x(n: Int) = xs[n]
    fun y(n: Int) = ys[n]
    fun from(e: Int) = ea[e]
    fun to(e: Int) = eb[e]
    fun way(e: Int) = eway[e]
    fun length(e: Int) = elen[e]
    fun other(e: Int, n: Int) = if (ea[e] == n) eb[e] else ea[e]

    /**
     * Tiles are simplified, which drops the shared vertex where a side road meets a straight one:
     * the side road's end then lies on the main road's segment instead of on a vertex. Those ends,
     * and vertices of ordinary roads lying on another ordinary road, are tied back in by splitting
     * the segment they touch. Bridges and tunnels only connect through such dangling ends.
     */
    private fun repairJunctions() {
        val cellAt = LongIntMap()
        val cells = ArrayList<IntBuf>()
        fun index(e: Int) = coveredCells(
            xs[ea[e]].toDouble(), ys[ea[e]].toDouble(), xs[eb[e]].toDouble(), ys[eb[e]].toDouble(), REPAIR_CELL, REPAIR_TOL,
        ) { c ->
            var slot = cellAt[c]
            if (slot < 0) { slot = cells.size; cells += IntBuf(4); cellAt.put(c, slot) }
            cells[slot].add(e)
        }
        for (e in 0 until edgeCount) index(e)
        val degree = IntArray(nodeCount)
        val plain = BooleanArray(nodeCount) { true }
        for (e in 0 until edgeCount) {
            degree[ea[e]]++; degree[eb[e]]++
            if (ways[eway[e]].brunnel != null) { plain[ea[e]] = false; plain[eb[e]] = false }
        }
        for (u in 0 until nodeCount) {
            val dangling = degree[u] == 1
            if (!dangling && !plain[u]) continue
            val tol = if (dangling) REPAIR_TOL else 1.0
            val level = plain[u]
            val px = xs[u].toDouble()
            val py = ys[u].toDouble()
            var best = -1
            var bestT = 0.0
            var bestD = tol
            val slot = cellAt[gridKey(Math.floorDiv(xs[u], REPAIR_CELL), Math.floorDiv(ys[u], REPAIR_CELL))]
            if (slot < 0) continue
            val list = cells[slot]
            for (k in 0 until list.size) {
                val e = list[k]
                val onGround = ways[eway[e]].brunnel == null
                if (ea[e] == u || eb[e] == u || (!dangling && !onGround)) continue
                val (t, d) = pointSegment(px, py, xs[ea[e]].toDouble(), ys[ea[e]].toDouble(), xs[eb[e]].toDouble(), ys[eb[e]].toDouble())
                // Beyond the usual drift, only join roads on the same level: a dead end near a bridge passes under it.
                if (d > NEAR_TOL && onGround != level) continue
                if (d <= bestD) { best = e; bestT = t; bestD = d }
            }
            if (best < 0) continue
            val a = ea[best]
            val b = eb[best]
            val len = elen[best]
            when {
                bestT * len < 0.5 -> connect(u, a, best)
                (1 - bestT) * len < 0.5 -> connect(u, b, best)
                else -> {
                    // Split a→b into a→u and u→b, keeping its direction and attributes.
                    eb[best] = u
                    elen[best] = edgeLength(a, u)
                    addEdge(u, b, eway[best])
                    index(edgeCount - 1)
                }
            }
        }
    }

    /** A short link from [u] to [v], passable both ways, on the road of edge [like]. */
    private fun connect(u: Int, v: Int, like: Int) {
        if (u == v) return
        val way = ways[eway[like]].copy(oneway = 0)
        addEdge(u, v, wayIndex.getOrPut(way) { ways += way; ways.lastIndex })
    }

    private fun edgeLength(a: Int, b: Int) =
        hypot((xs[b] - xs[a]).toDouble(), (ys[b] - ys[a]).toDouble()) * metersPerUnit((ys[a] + ys[b]) / 2.0)

    private fun addEdge(a: Int, b: Int, w: Int) {
        val e = ea.size
        ea.add(a)
        eb.add(b)
        eway.add(w)
        if (e == elen.size) elen = elen.copyOf(e * 2)
        elen[e] = edgeLength(a, b)
    }

    /** The node at a grid point, reusing one within a unit (or a few units along a tile edge we cut at). */
    private fun node(x: Double, y: Double, cut: Boolean): Int {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        nodeAt[key(ix, iy)].let { if (it >= 0) return it }
        val r = if (cut) 3 else 1
        var best = -1
        var bestD = Int.MAX_VALUE
        for (dx in -r..r) for (dy in -r..r) {
            val n = nodeAt[key(ix + dx, iy + dy)]
            if (n < 0) continue
            val d = dx * dx + dy * dy
            if (d < bestD) { best = n; bestD = d }
        }
        if (best >= 0) return best
        xs.add(ix)
        ys.add(iy)
        return (xs.size - 1).also { nodeAt.put(key(ix, iy), it) }
    }

    private fun buildAdjacency() {
        val n = nodeCount
        val start = IntArray(n + 1)
        for (e in 0 until edgeCount) { start[ea[e] + 1]++; start[eb[e] + 1]++ }
        for (i in 0 until n) start[i + 1] += start[i]
        val fill = start.copyOf()
        val adj = IntArray(edgeCount * 2)
        for (e in 0 until edgeCount) { adj[fill[ea[e]]++] = e; adj[fill[eb[e]]++] = e }
        adjStart = start
        adjEdge = adj
    }

    companion object {
        private const val REPAIR_CELL = 32
        /** Dead ends this close (in grid units, about 0.5 m each) to another road are joined to it. */
        private const val REPAIR_TOL = 5.0
        private const val NEAR_TOL = 2.0

        private fun key(x: Int, y: Int) = gridKey(x, y)

        /** Liang–Barsky: the parameter range of the segment inside the box, or null if it misses. */
        fun clip(x1: Double, y1: Double, x2: Double, y2: Double, minX: Double, minY: Double, maxX: Double, maxY: Double): Pair<Double, Double>? {
            var t0 = 0.0
            var t1 = 1.0
            val dx = x2 - x1
            val dy = y2 - y1
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(x1 - minX, maxX - x1, y1 - minY, maxY - y1)
            for (i in 0 until 4) {
                if (p[i] == 0.0) {
                    if (q[i] < 0) return null
                } else {
                    val r = q[i] / p[i]
                    if (p[i] < 0) { if (r > t1) return null; if (r > t0) t0 = r }
                    else { if (r < t0) return null; if (r < t1) t1 = r }
                }
            }
            return if (t1 - t0 > 1e-9) t0 to t1 else null
        }
    }
}

private const val EARTH_CIRCUMFERENCE = 40_075_016.686

/** Metres per global grid unit at global row [gy]. */
internal fun metersPerUnit(gy: Double) = EARTH_CIRCUMFERENCE / cosh(PI * (1 - 2 * gy / WORLD)) / WORLD // cos(lat) = 1 / cosh(y)

internal fun globalToLatLon(gx: Double, gy: Double): LatLon = mercatorToLatLon(gx / WORLD, gy / WORLD)

internal fun latLonToGlobal(p: LatLon) = lonToFraction(p.lon) * WORLD to latToFraction(p.lat) * WORLD

/** Street names from `transportation_name`, hashed into grid cells to label route segments. */
internal class NameIndex {
    private val segs = IntBuf(1024)
    private val segName = IntBuf(256)
    private val names = ArrayList<String>()
    private val nameIds = HashMap<String, Int>()
    private val cells = HashMap<Long, IntBuf>()

    fun add(layer: MvtLayer, ox: Double, oy: Double) {
        val scale = GLOBAL_EXTENT.toDouble() / layer.extent
        for (f in layer.features) {
            if (f.type != GeomType.Line) continue
            val name = f.string("name_en") ?: f.string("name") ?: f.string("ref") ?: continue
            val id = nameIds.getOrPut(name) { names += name; names.lastIndex }
            for (part in f.geometry) for (i in 2 until part.size step 2) {
                val x1 = (ox + part[i - 2] * scale).toInt()
                val y1 = (oy + part[i - 1] * scale).toInt()
                val x2 = (ox + part[i] * scale).toInt()
                val y2 = (oy + part[i + 1] * scale).toInt()
                val s = segName.size
                segs.add(x1); segs.add(y1); segs.add(x2); segs.add(y2)
                segName.add(id)
                coveredCells(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), CELL, MAX_UNITS) { c ->
                    cells.getOrPut(c) { IntBuf(4) }.add(s)
                }
            }
        }
    }

    /** Name of the named line running along segment a→b, or "" if none is close and parallel. */
    fun lookup(ax: Double, ay: Double, bx: Double, by: Double): String {
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        val dir = Math.toDegrees(atan2(by - ay, bx - ax))
        var best = -1
        var bestD = MAX_UNITS
        val list = cells[gridKey(floor(mx / CELL).toInt(), floor(my / CELL).toInt())] ?: return ""
        for (k in 0 until list.size) {
            val s = list[k]
            val x1 = segs[s * 4].toDouble(); val y1 = segs[s * 4 + 1].toDouble()
            val x2 = segs[s * 4 + 2].toDouble(); val y2 = segs[s * 4 + 3].toDouble()
            val d = pointSegment(mx, my, x1, y1, x2, y2).second
            if (d >= bestD) continue
            var diff = abs(Math.toDegrees(atan2(y2 - y1, x2 - x1)) - dir) % 180
            if (diff > 90) diff = 180 - diff
            if (diff > MAX_ANGLE && hypot(bx - ax, by - ay) > 4) continue
            best = s
            bestD = d
        }
        return if (best < 0) "" else names[segName[best]]
    }

    private companion object {
        const val CELL = 64
        const val MAX_UNITS = 12.0
        const val MAX_ANGLE = 25.0
    }
}

internal fun gridKey(x: Int, y: Int) = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)

/** Calls [action] with the key of every [cell]-sized grid cell that has a point within [tol] of the segment. */
internal inline fun coveredCells(x1: Double, y1: Double, x2: Double, y2: Double, cell: Int, tol: Double, action: (Long) -> Unit) {
    val minX = min(x1, x2)
    val maxX = max(x1, x2)
    for (cx in floor((minX - tol) / cell).toInt()..floor((maxX + tol) / cell).toInt()) {
        // The segment's y range over this column, widened by the tolerance on every side.
        val lo = max(cx * cell.toDouble() - tol, minX)
        val hi = min((cx + 1) * cell.toDouble() + tol, maxX)
        val ya = if (maxX - minX < 1e-9) y1 else y1 + (y2 - y1) * (lo - x1) / (x2 - x1)
        val yb = if (maxX - minX < 1e-9) y2 else y1 + (y2 - y1) * (hi - x1) / (x2 - x1)
        for (cy in floor((min(ya, yb) - tol) / cell).toInt()..floor((max(ya, yb) + tol) / cell).toInt()) action(gridKey(cx, cy))
    }
}

/** Closest point of segment (x1,y1)-(x2,y2) to (px,py), as (fraction along the segment, distance). */
internal fun pointSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Pair<Double, Double> {
    val dx = x2 - x1
    val dy = y2 - y1
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0.0, 1.0)
    return Pair(t, hypot(px - (x1 + dx * t), py - (y1 + dy * t)))
}

/** Open-addressing Long → Int map (missing keys read as -1), to avoid boxing on large graphs. */
internal class LongIntMap(capacity: Int = 1024) {
    private var keys = LongArray(capacity)
    private var values = IntArray(capacity) { -1 }
    private var size = 0

    operator fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (values[i] >= 0) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Long, value: Int) {
        if (size * 2 >= keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (values[i] >= 0) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
        keys[i] = key
        values[i] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2) { -1 }
        size = 0
        for (i in oldKeys.indices) if (oldValues[i] >= 0) put(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h xor (h ushr 32)).toInt()
    }
}
