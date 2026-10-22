package com.shantanu.nmaps.gpx

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.distanceMeters
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

data class GpxPoint(val lat: Double, val lon: Double, val ele: Double? = null, val time: Long? = null) {
    val latLon get() = LatLon(lat, lon)
}

data class GpxWaypoint(val name: String, val description: String, val lat: Double, val lon: Double, val ele: Double? = null)

/** A track or a route; GPX routes (rte) are read as tracks without timestamps. */
data class GpxTrack(val name: String?, val type: String?, val points: List<GpxPoint>)

data class GpxFile(val name: String?, val tracks: List<GpxTrack>, val waypoints: List<GpxWaypoint>) {
    val isEmpty get() = tracks.all { it.points.isEmpty() } && waypoints.isEmpty()
}

object Gpx {

    class InvalidGpx(message: String) : Exception(message)

    /** Streams the file with SAX so a 100k-point track doesn't need a DOM in memory. */
    fun parse(input: InputStream): GpxFile {
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // GPX never needs external entities; refusing them blocks XXE tricks in shared files.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        try {
            factory.newSAXParser().parse(input, handler)
        } catch (e: Exception) {
            throw InvalidGpx("This file isn't a readable GPX file.")
        }
        if (!handler.sawGpx) throw InvalidGpx("This file isn't a GPX file.")
        return GpxFile(handler.fileName, handler.tracks.filter { it.points.isNotEmpty() }, handler.waypoints)
    }

    private class Handler : DefaultHandler() {
        var sawGpx = false
        var fileName: String? = null
        val tracks = mutableListOf<GpxTrack>()
        val waypoints = mutableListOf<GpxWaypoint>()

        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var trackName: String? = null
        private var trackType: String? = null
        private var trackPoints = mutableListOf<GpxPoint>()
        private var ptLat = 0.0
        private var ptLon = 0.0
        private var ptEle: Double? = null
        private var ptTime: Long? = null
        private var ptName = ""
        private var ptDesc = ""

        private fun name(localName: String?, qName: String?) =
            (localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':').orEmpty()).lowercase(Locale.US)

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
            val n = name(localName, qName)
            text.setLength(0)
            when (n) {
                "gpx" -> sawGpx = true
                "trk", "rte" -> {
                    trackName = null
                    trackType = null
                    trackPoints = mutableListOf()
                }
                "trkpt", "rtept", "wpt" -> {
                    ptLat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                    ptLon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                    ptEle = null
                    ptTime = null
                    ptName = ""
                    ptDesc = ""
                }
            }
            path.addLast(n)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val n = name(localName, qName)
            path.removeLastOrNull()
            val parent = path.lastOrNull()
            val value = text.toString().trim()
            when (n) {
                "ele" -> ptEle = value.toDoubleOrNull()
                "time" -> if (parent in setOf("trkpt", "rtept", "wpt")) ptTime = parseTime(value)
                "name" -> when (parent) {
                    "trk", "rte" -> trackName = value
                    "wpt" -> ptName = value
                    "metadata", "gpx" -> fileName = value
                }
                "desc", "cmt" -> if (parent == "wpt" && ptDesc.isEmpty()) ptDesc = value
                "type" -> if (parent == "trk" || parent == "rte") trackType = value
                "trkpt", "rtept" -> if (valid(ptLat, ptLon)) trackPoints += GpxPoint(ptLat, ptLon, ptEle, ptTime)
                "wpt" -> if (valid(ptLat, ptLon)) waypoints += GpxWaypoint(ptName.ifEmpty { "Waypoint" }, ptDesc, ptLat, ptLon, ptEle)
                "trk", "rte" -> tracks += GpxTrack(trackName, trackType, trackPoints)
            }
            text.setLength(0)
        }

        private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
    }

    /** ISO-8601 in the forms GPX writers actually emit: Z, offsets, fractions, or no zone (taken as UTC). */
    fun parseTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    private val isoFormat = DateTimeFormatter.ISO_INSTANT

    fun write(name: String, tracks: List<GpxTrack> = emptyList(), waypoints: List<GpxWaypoint> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Maps" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>${name.xml()}</name></metadata>\n")
        waypoints.forEach { w ->
            append("""  <wpt lat="${w.lat}" lon="${w.lon}">""")
            w.ele?.let { append("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
            append("<name>${w.name.xml()}</name>")
            if (w.description.isNotBlank()) append("<desc>${w.description.xml()}</desc>")
            append("</wpt>\n")
        }
        tracks.forEach { t ->
            append("  <trk><name>${(t.name ?: name).xml()}</name>")
            t.type?.let { append("<type>${it.xml()}</type>") }
            append("<trkseg>\n")
            t.points.forEach { p ->
                append("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.ele?.let { append("<ele>${"%.1f".format(Locale.US, it)}</ele>") }
                p.time?.let { append("<time>${isoFormat.format(Instant.ofEpochMilli(it))}</time>") }
                append("</trkpt>\n")
            }
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private fun String.xml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Writes GPX text into the cache folder that the FileProvider shares, named safely. */
fun shareableGpx(context: android.content.Context, baseName: String, body: String): java.io.File {
    val out = java.io.File(context.cacheDir, "gpx").apply { mkdirs() }
    val safe = baseName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
    return java.io.File(out, "$safe.gpx").apply { writeText(body) }
}

/** Summary numbers for a track, and a downsampled elevation profile for drawing. */
data class TrackStats(
    val distance: Double,
    val gain: Double,
    val loss: Double,
    val minEle: Double?,
    val maxEle: Double?,
    /** Elapsed time from first to last timestamp, when the track has times. */
    val durationMs: Long?,
    /** (distance along track, elevation) pairs, at most [PROFILE_SAMPLES] of them. */
    val profile: List<Pair<Double, Double>>,
) {
    /** Naismith's rule: 5 km/h on the flat plus one minute per 10 m of climbing. */
    val hikingSeconds get() = distance / 1000 / 5.0 * 3600 + gain / 10 * 60

    /** A relaxed 18 km/h plus one minute per 12 m of climbing. */
    val cyclingSeconds get() = distance / 1000 / 18.0 * 3600 + gain / 12 * 60

    companion object {
        const val PROFILE_SAMPLES = 120

        fun of(points: List<GpxPoint>, hysteresis: Double = 3.0): TrackStats {
            var dist = 0.0
            var gain = 0.0
            var loss = 0.0
            var ref: Double? = null
            val along = DoubleArray(points.size)
            for (i in points.indices) {
                if (i > 0) dist += distanceMeters(points[i - 1].latLon, points[i].latLon)
                along[i] = dist
                val e = points[i].ele ?: continue
                // Only count climbs and drops bigger than GPS/DEM noise.
                val r = ref
                if (r == null) ref = e
                else if (e - r >= hysteresis) { gain += e - r; ref = e }
                else if (r - e >= hysteresis) { loss += r - e; ref = e }
            }
            val eles = points.mapNotNull { it.ele }
            val times = points.mapNotNull { it.time }
            val withEle = points.indices.filter { points[it].ele != null }
            val step = (withEle.size / PROFILE_SAMPLES.toDouble()).coerceAtLeast(1.0)
            val profile = if (withEle.size < 2) emptyList() else
                (0 until minOf(PROFILE_SAMPLES, withEle.size)).map { k ->
                    val i = withEle[(k * step).toInt().coerceAtMost(withEle.lastIndex)]
                    along[i] to points[i].ele!!
                }
            return TrackStats(
                distance = dist,
                gain = gain,
                loss = loss,
                minEle = eles.minOrNull(),
                maxEle = eles.maxOrNull(),
                durationMs = if (times.size >= 2) times.max() - times.min() else null,
                profile = profile,
            )
        }
    }
}
