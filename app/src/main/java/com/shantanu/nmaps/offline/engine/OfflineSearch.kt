package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.nav.distanceMeters
import java.text.Normalizer
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Name search over the downloaded tiles: POIs, localities, streets, parks and water. The index is
 * built from every z14 tile on first use; call [invalidate] when areas are added or removed.
 */
class OfflineSearch(private val source: TileSource) {
    @Volatile private var index: List<Entry>? = null

    fun invalidate() {
        index = null
    }

    fun search(query: String, near: LatLon? = null, limit: Int = 20): List<Place> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(' ')
        val entries = index()
        val hits = entries.mapNotNull { e -> match(e, q, tokens)?.let { Hit(e, it.first, it.second, near?.let { n -> distanceMeters(n, e.point) } ?: 0.0) } }
            .sortedWith(compareBy<Hit>({ it.tier }, { it.distance }, { it.entry.rank }))
        val kept = ArrayList<Hit>()
        for (h in hits) {
            if (kept.any { it.name.equals(h.name, ignoreCase = true) && distanceMeters(it.entry.point, h.entry.point) < DUPLICATE_M }) continue
            kept += h
            if (kept.size == limit) break
        }
        val localities = entries.filter { it.locality }
        return kept.map { h ->
            val town = localities.filter { it !== h.entry }.minByOrNull { distanceMeters(it.point, h.entry.point) }
                ?.takeIf { distanceMeters(it.point, h.entry.point) < LOCALITY_M && it.names[0] != h.name }?.names?.get(0)
            Place(h.name, listOfNotNull(h.entry.kind, town).joinToString(", "), h.entry.point.lat, h.entry.point.lon)
        }
    }

    private fun index(): List<Entry> = index ?: synchronized(this) { index ?: build().also { index = it } }

    private fun build(): List<Entry> {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        val kinds = HashMap<String, List<String>>()
        fun add(names: List<String>, kind: String, p: LatLon, rank: Int, locality: Boolean = false) {
            val distinct = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (distinct.isEmpty()) return
            if (!seen.add("${distinct[0]}|${(p.lat * 1e5).roundToLong()}|${(p.lon * 1e5).roundToLong()}")) return
            val kindTokens = kinds.getOrPut(kind) { normalize(kind).split(' ') }
            entries += Entry(distinct, distinct.map(::normalize), kind, kindTokens, p, rank, locality)
        }
        for (id in source.tiles(GLOBAL_ZOOM)) {
            val tile = source.tile(id)?.let { runCatching { MvtTile.decode(it) }.getOrNull() } ?: continue
            fun at(layer: MvtLayer, px: Double, py: Double) = tileToLatLon(id.z, id.x, id.y, layer.extent, px, py)
            fun names(f: MvtFeature) = listOfNotNull(f.string("name"), f.string("name_en"), f.string("name:en"))

            tile.layer("poi")?.let { layer ->
                for (f in layer.features) {
                    val n = names(f).takeIf { it.isNotEmpty() } ?: continue
                    val g = f.geometry.firstOrNull() ?: continue
                    val kind = pretty(f.string("subclass") ?: f.string("class") ?: "place")
                    add(n, kind, at(layer, g[0].toDouble(), g[1].toDouble()), f.long("rank")?.toInt() ?: 99)
                }
            }
            tile.layer("place")?.let { layer ->
                for (f in layer.features) {
                    val g = f.geometry.firstOrNull() ?: continue
                    val cls = f.string("class") ?: continue
                    add(names(f), pretty(cls), at(layer, g[0].toDouble(), g[1].toDouble()), f.long("rank")?.toInt() ?: 50, cls in LOCALITIES)
                }
            }
            tile.layer("transportation_name")?.let { layer ->
                // A street is cut into many features; one entry per name per tile is enough.
                layer.features.filter { it.string("name") != null || it.string("ref") != null }
                    .groupBy { it.string("name") ?: it.string("ref") }
                    .forEach { (_, fs) ->
                        val part = fs.flatMap { it.geometry }.maxByOrNull { length(it) } ?: return@forEach
                        val (px, py) = midpoint(part)
                        val f = fs[0]
                        val kind = if (f.string("class") in setOf("path", "track")) "Path" else "Street"
                        add(names(f) + listOfNotNull(f.string("ref")), kind, at(layer, px, py), 60)
                    }
            }
            for ((layerName, fallback) in listOf("water_name" to "Water", "park" to "Park")) {
                val layer = tile.layer(layerName) ?: continue
                for (f in layer.features) {
                    val n = names(f).takeIf { it.isNotEmpty() } ?: continue
                    val part = f.geometry.maxByOrNull { it.size } ?: continue
                    val (px, py) = if (f.type == GeomType.Line) midpoint(part) else centroid(part)
                    add(n, f.string("class")?.let(::pretty) ?: fallback, at(layer, px, py), 40)
                }
            }
        }
        return entries
    }

    private class Entry(
        val names: List<String>,
        val norms: List<String>,
        val kind: String,
        val kindTokens: List<String>,
        val point: LatLon,
        val rank: Int,
        val locality: Boolean,
    ) {
        val tokens = norms.map { it.split(' ') }
    }

    private class Hit(val entry: Entry, val tier: Int, val name: String, val distance: Double)

    /** Best (tier, display name) of [e] for the query: exact, starts-with, word-prefix, contains, then by kind. */
    private fun match(e: Entry, q: String, qTokens: List<String>): Pair<Int, String>? {
        val first = qTokens[0]
        if (e.norms.none { it.contains(first) } && e.kindTokens.none { it.startsWith(first) }) return null
        var best: Pair<Int, String>? = null
        for (i in e.norms.indices) {
            val n = e.norms[i]
            val words = e.tokens[i]
            val tier = when {
                n == q -> 0
                n.startsWith(q) -> 1
                qTokens.all { t -> words.any { it.startsWith(t) } } -> 2
                n.contains(q) || qTokens.all { n.contains(it) } -> 3
                qTokens.all { t -> (words + e.kindTokens).any { it.startsWith(t) } } -> 4
                else -> continue
            }
            if (best == null || tier < best.first) best = tier to e.names[i]
        }
        return best
    }

    private companion object {
        const val DUPLICATE_M = 150.0
        const val LOCALITY_M = 3000.0
        val LOCALITIES = setOf("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "isolated_dwelling")
        /** Lower case, accents dropped, anything but letters and digits as single spaces. */
        fun normalize(s: String): String {
            val src = if (s.all { it.code < 128 }) s else Normalizer.normalize(s, Normalizer.Form.NFD)
            val out = StringBuilder(src.length)
            for (c in src) {
                when {
                    Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() } -> Unit
                    c.isLetterOrDigit() -> out.append(c.lowercaseChar())
                    out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
                }
            }
            return out.trimEnd().toString()
        }

        fun pretty(tag: String) = tag.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

        fun length(p: IntArray) = (2 until p.size step 2).sumOf { hypot((p[it] - p[it - 2]).toDouble(), (p[it + 1] - p[it - 1]).toDouble()) }

        /** The point halfway along a line. */
        fun midpoint(p: IntArray): Pair<Double, Double> {
            var left = length(p) / 2
            for (i in 2 until p.size step 2) {
                val d = hypot((p[i] - p[i - 2]).toDouble(), (p[i + 1] - p[i - 1]).toDouble())
                if (d >= left && d > 0) {
                    val t = left / d
                    return p[i - 2] + (p[i] - p[i - 2]) * t to p[i - 1] + (p[i + 1] - p[i - 1]) * t
                }
                left -= d
            }
            return p[0].toDouble() to p[1].toDouble()
        }

        fun centroid(p: IntArray): Pair<Double, Double> {
            val n = p.size / 2
            return (0 until n).sumOf { p[it * 2].toDouble() } / n to (0 until n).sumOf { p[it * 2 + 1].toDouble() } / n
        }
    }
}
