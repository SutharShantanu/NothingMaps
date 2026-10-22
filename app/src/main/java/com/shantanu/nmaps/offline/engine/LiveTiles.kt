package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.Http
import com.shantanu.nmaps.data.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Downloaded tiles first, then tiles fetched on demand from OpenFreeMap — the same vector data the
 * map draws. Lets the on-phone engine read roads along any route, not only in downloaded areas.
 */
class LiveTileSource(private val downloaded: TileSource) : TileSource {
    private val fetched = ConcurrentHashMap<TileId, ByteArray>()
    @Volatile private var template: String? = null

    override fun tiles(z: Int): List<TileId> = (downloaded.tiles(z) + fetched.keys.filter { it.z == z }).distinct()

    override fun tile(id: TileId): ByteArray? = fetched[id] ?: downloaded.tile(id)

    /** z14 tiles covering [points] plus [marginM] metres, fetching any not already on the phone. */
    suspend fun ensure(points: List<LatLon>, marginM: Double, fetch: Boolean): Set<TileId> {
        val ids = cover(points, marginM)
        if (!fetch) return ids
        val have = downloaded.tiles(GLOBAL_ZOOM).toHashSet()
        // Keep memory bounded across many routes: start over once the cache is large.
        if (fetched.size > MAX_CACHED) fetched.clear()
        val missing = ids.filter { !fetched.containsKey(it) && it !in have }
        if (missing.isEmpty()) return ids
        // A route's corridor at z14 is tens of tiles; cap it so one request can't download a city.
        require(missing.size <= MAX_FETCH) { "Area too large to read road details for" }
        val url = template ?: resolveTemplate()
        val gate = Semaphore(4)
        coroutineScope {
            missing.map { id ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching { download(url, id) }.getOrNull()?.let { fetched[id] = it }
                    }
                }
            }.awaitAll()
        }
        return ids
    }

    private suspend fun resolveTemplate(): String {
        val tiles = JSONObject(Http.text("https://tiles.openfreemap.org/planet")).getJSONArray("tiles")
        return tiles.getString(0).also { template = it }
    }

    private suspend fun download(template: String, id: TileId): ByteArray = withContext(Dispatchers.IO) {
        val url = template.replace("{z}", "${id.z}").replace("{x}", "${id.x}").replace("{y}", "${id.y}")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", Http.USER_AGENT)
        try {
            // HttpURLConnection undoes gzip transfer encoding itself, so this is the raw MVT.
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_FETCH = 160
        private const val MAX_CACHED = 400

        /**
         * Tiles within [marginM] of the line through [points] — a corridor, not the bounding box, so a
         * long diagonal drive needs tens of tiles rather than hundreds.
         */
        fun cover(points: List<LatLon>, marginM: Double): Set<TileId> {
            val n = (1 shl GLOBAL_ZOOM).toDouble()
            val line = if (points.size < 2) points else com.shantanu.nmaps.data.Terrain.resample(points, 500.0, 2000)
            val dLat = marginM / 111_320.0
            return buildSet {
                for (p in line) {
                    val dLon = marginM / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)))
                    val x0 = floor(lonToFraction(p.lon - dLon) * n).toInt()
                    val x1 = floor(lonToFraction(p.lon + dLon) * n).toInt()
                    val y0 = floor(latToFraction(p.lat + dLat) * n).toInt()
                    val y1 = floor(latToFraction(p.lat - dLat) * n).toInt()
                    for (x in x0..x1) for (y in y0..y1) add(TileId(GLOBAL_ZOOM, x, y))
                }
            }
        }
    }
}
