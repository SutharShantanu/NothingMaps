package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.Instructions
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Structure
import com.shantanu.nmaps.data.Terrain
import com.shantanu.nmaps.nav.distanceMeters
import kotlin.math.hypot

/**
 * Reads the roads a route runs on from the vector tiles, to learn what OSRM's public server
 * doesn't report: which parts are tolled, motorway or ferry, and which turns lead onto a flyover or
 * into an underpass.
 */
class RouteAnnotator(private val source: TileSource) {

    private class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val cls: String, val toll: Boolean, val brunnel: String?)

    /** What the road nearest to one sample point is. */
    private class Hit(val cls: String, val toll: Boolean, val brunnel: String?)

    fun annotate(route: Route): Route {
        if (route.points.size < 2 || route.isTrack) return route
        val samples = Terrain.resample(route.points, SPACING, 1500)
        val segs = HashMap<TileId, List<Seg>>()
        val hits = samples.map { p -> nearest(p, segs) }

        val classes = buildSet {
            if (hits.any { it?.toll == true }) add("toll")
            if (hits.any { it?.cls == "motorway" }) add("motorway")
            if (hits.any { it?.cls == "ferry" }) add("ferry")
        }

        // For each manoeuvre, look at the road just after it: a run of bridge on a main road is a
        // flyover; tunnel is an underpass. Short bridges (over drains) don't count.
        val steps = route.steps.map { step ->
            if (step.structure != null || step.type in setOf("depart", "arrive", "roundabout", "rotary", "exit roundabout", "exit rotary")) return@map step
            val start = samples.indices.minByOrNull { distanceMeters(samples[it], step.at) } ?: return@map step
            val after = (start + 1..minOf(start + 6, hits.lastIndex)).mapNotNull { hits[it] }
            val structure = when {
                after.count { it.brunnel == "tunnel" } >= 2 -> Structure.Underpass
                after.count { it.brunnel == "bridge" && it.cls in MAIN_ROADS } >= 3 -> Structure.Flyover
                else -> null
            } ?: return@map step
            step.copy(structure = structure, instruction = Instructions.text(step.type, step.modifier, step.road, null, structure))
        }
        val withEntries = insertStructureEntries(steps, samples, hits)
        // Stop arrivals keep their leg boundaries after the inserted steps shift indices.
        val legEnds = withEntries.indices.filter { withEntries[it].type == "arrive" }
        return route.copy(classes = route.classes + classes, steps = withEntries, legEnds = if (route.legEnds.isEmpty()) emptyList() else legEnds)
    }

    /**
     * Going straight into an underpass or onto a flyover has no OSRM manoeuvre, yet it's the moment
     * that matters where a service road runs alongside. Add a step where the route enters one.
     */
    private fun insertStructureEntries(steps: List<com.shantanu.nmaps.data.Step>, samples: List<LatLon>, hits: List<Hit?>): List<com.shantanu.nmaps.data.Step> {
        val entries = mutableListOf<Pair<Int, Structure>>()
        var i = 0
        while (i < hits.size) {
            val kind = structureAt(hits[i])
            if (kind == null) { i++; continue }
            var j = i
            while (j + 1 < hits.size && structureAt(hits[j + 1]) == kind) j++
            val run = j - i + 1
            if ((kind == Structure.Underpass && run >= 2) || (kind == Structure.Flyover && run >= 3)) entries += i to kind
            i = j + 1
        }
        if (entries.isEmpty()) return steps
        // Order everything by where it happens along the route.
        val indexed = steps.map { st -> samples.indices.minByOrNull { distanceMeters(samples[it], st.at) }!! to st }.toMutableList()
        for ((at, kind) in entries) {
            // Skip when a real manoeuvre just before already says it.
            val covered = indexed.any { (k, st) -> st.structure == kind && at - k in 0..6 }
            if (covered || at == 0) continue
            val entry = com.shantanu.nmaps.data.Step(
                Instructions.text("continue", "straight", "", null, kind), "continue", "straight", 0.0, samples[at], structure = kind,
            )
            // By position only: a stop's "arrive" belongs where it happens, not at the end of every leg.
            val pos = indexed.indexOfFirst { (k, _) -> k > at }.let { if (it < 0) indexed.size else it }
            indexed.add(pos, at to entry)
        }
        return indexed.map { it.second }
    }

    private fun structureAt(h: Hit?): Structure? = when {
        h == null -> null
        h.brunnel == "tunnel" && h.cls in MAIN_ROADS -> Structure.Underpass
        h.brunnel == "bridge" && h.cls in MAIN_ROADS -> Structure.Flyover
        else -> null
    }

    private fun nearest(p: LatLon, cache: HashMap<TileId, List<Seg>>): Hit? {
        val gx = lonToFraction(p.lon) * WORLD
        val gy = latToFraction(p.lat) * WORLD
        val tx = (gx / GLOBAL_EXTENT).toInt()
        val ty = (gy / GLOBAL_EXTENT).toInt()
        val maxUnits = MAX_METERS / metersPerUnit(gy)
        var best: Seg? = null
        var bestD = maxUnits
        for (dx in -1..1) for (dy in -1..1) {
            val id = TileId(GLOBAL_ZOOM, tx + dx, ty + dy)
            for (s in cache.getOrPut(id) { segments(id) }) {
                val d = distance(gx, gy, s)
                if (d < bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        return best?.let { Hit(it.cls, it.toll, it.brunnel) }
    }

    private fun segments(id: TileId): List<Seg> {
        val bytes = source.tile(id) ?: return emptyList()
        val layer = runCatching { MvtTile.decode(bytes).layer("transportation") }.getOrNull() ?: return emptyList()
        val scale = GLOBAL_EXTENT.toDouble() / layer.extent
        val out = ArrayList<Seg>()
        for (f in layer.features) {
            if (f.type != GeomType.Line) continue
            val cls = f.string("class") ?: continue
            val toll = f.get("toll").let { it == 1L || it == 1 || it == true || it == "yes" || it == 1.0 }
            val brunnel = f.string("brunnel")
            for (part in f.geometry) {
                var i = 0
                while (i + 3 < part.size) {
                    out += Seg(
                        id.x * GLOBAL_EXTENT + part[i] * scale, id.y * GLOBAL_EXTENT + part[i + 1] * scale,
                        id.x * GLOBAL_EXTENT + part[i + 2] * scale, id.y * GLOBAL_EXTENT + part[i + 3] * scale,
                        cls, toll, brunnel,
                    )
                    i += 2
                }
            }
        }
        return out
    }

    private fun distance(px: Double, py: Double, s: Seg): Double {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - s.ax) * dx + (py - s.ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(s.ax + t * dx - px, s.ay + t * dy - py)
    }

    companion object {
        private const val SPACING = 25.0
        private const val MAX_METERS = 10.0
        private val MAIN_ROADS = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
    }
}
