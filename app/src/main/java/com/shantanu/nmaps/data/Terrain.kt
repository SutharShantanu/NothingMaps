package com.shantanu.nmaps.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.shantanu.nmaps.gpx.GpxPoint
import com.shantanu.nmaps.gpx.TrackStats
import com.shantanu.nmaps.nav.distanceMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Elevation from the same open terrain tiles the hillshade layer uses (AWS Terrarium PNGs):
 * each pixel stores height as (R·256 + G + B/256) − 32768 metres.
 */
object Terrain {
    private const val ZOOM = 12
    private val cache = LruCache<String, Bitmap>(32)

    fun decode(r: Int, g: Int, b: Int): Double = r * 256.0 + g + b / 256.0 - 32768.0

    /** Climb, descent and a profile for a route, sampled about every [spacing] metres. */
    suspend fun stats(points: List<LatLon>, spacing: Double = 40.0, maxSamples: Int = 250): TrackStats? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        val samples = resample(points, spacing, maxSamples)
        val raw = samples.map { p -> GpxPoint(p.lat, p.lon, elevation(p)) }.filter { it.ele != null }
        if (raw.size < 2) return@withContext null
        // The terrain grid is ~30 m and noisy; climb counted from raw samples overstates hills badly.
        val smooth = smooth(raw.map { it.ele!! })
        TrackStats.of(raw.mapIndexed { i, p -> p.copy(ele = smooth[i]) }, hysteresis = 3.0)
    }

    private fun elevation(p: LatLon): Double? {
        val n = 2.0.pow(ZOOM)
        val wx = (p.lon + 180) / 360 * n * 256
        val r = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
        val wy = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n * 256
        val tx = floor(wx / 256).toInt()
        val ty = floor(wy / 256).toInt()
        val bmp = tile(tx, ty) ?: return null
        val px = (wx - tx * 256).toInt().coerceIn(0, 255)
        val py = (wy - ty * 256).toInt().coerceIn(0, 255)
        val c = bmp.getPixel(px, py)
        return decode((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }

    private fun tile(x: Int, y: Int): Bitmap? {
        val key = "$x/$y"
        cache.get(key)?.let { return it }
        return runCatching {
            val conn = URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$ZOOM/$x/$y.png").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", Http.USER_AGENT)
            try {
                // Exact pixel values matter here, so no scaling or colour conversion.
                conn.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.also { cache.put(key, it) }
    }

    /** Centred moving average over [window] samples (about 200 m at the default spacing). */
    fun smooth(values: List<Double>, window: Int = 5): List<Double> = values.indices.map { i ->
        val from = (i - window / 2).coerceAtLeast(0)
        val to = (i + window / 2).coerceAtMost(values.lastIndex)
        values.subList(from, to + 1).average()
    }

    /** Points spaced evenly along the line, at most [max] of them. */
    fun resample(points: List<LatLon>, spacing: Double, max: Int): List<LatLon> {
        val total = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        val step = maxOf(spacing, total / max)
        val out = mutableListOf(points.first())
        var carry = 0.0
        for ((a, b) in points.zipWithNext()) {
            val d = distanceMeters(a, b)
            var t = step - carry
            while (t <= d) {
                val f = t / d
                out += LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                t += step
            }
            carry = (carry + d) % step
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }
}
