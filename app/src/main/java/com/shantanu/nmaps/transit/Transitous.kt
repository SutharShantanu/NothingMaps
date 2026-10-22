package com.shantanu.nmaps.transit

import com.shantanu.nmaps.data.Http
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.pathLength
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale

/**
 * Public-transit trip planning on Transitous (https://transitous.org), a community-run MOTIS 2
 * instance over open GTFS feeds. Its usage policy asks for a non-commercial, open-source client
 * that identifies itself, keeps request volume low and links https://transitous.org/sources/.
 */
object Transitous {
    private const val PLAN = "https://api.transitous.org/api/v6/plan"

    /**
     * Itineraries leaving at [time] (epoch millis), or arriving by it when [arriveBy] is set.
     * Transit options come first, then a direct walk if the server offers one.
     */
    suspend fun plan(
        from: LatLon,
        to: LatLon,
        time: Long = System.currentTimeMillis(),
        arriveBy: Boolean = false,
    ): List<TransitItinerary> {
        val url = "$PLAN?fromPlace=${coord(from)}&toPlace=${coord(to)}" +
            "&time=${Instant.ofEpochMilli(time)}&arriveBy=$arriveBy&numItineraries=5"
        val json = Http.json(url)
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException("Transit planner error: $it") }
        return parse(json).ifEmpty { throw IOException("No transit connections found for this trip") }
    }

    private fun coord(p: LatLon) = "%.6f,%.6f".format(Locale.US, p.lat, p.lon)

    internal fun parse(json: JSONObject): List<TransitItinerary> =
        (json.optJSONArray("itineraries").objects() + json.optJSONArray("direct").objects()).map(::itinerary)

    private fun itinerary(o: JSONObject): TransitItinerary {
        val legs = o.getJSONArray("legs").objects().map(::leg)
        return TransitItinerary(
            start = time(o.getString("startTime")),
            end = time(o.getString("endTime")),
            durationSec = o.getLong("duration"),
            transfers = o.optInt("transfers"),
            walkMeters = legs.filter { it.mode == TransitMode.Walk }.sumOf { it.distance },
            legs = legs,
        )
    }

    private fun leg(o: JSONObject): TransitLeg {
        val geometry = o.getJSONObject("legGeometry")
        val points = decodePolyline(geometry.getString("points"), geometry.getInt("precision"))
        val mode = TransitMode.of(o.getString("mode"))
        return TransitLeg(
            mode = mode,
            from = stop(o.getJSONObject("from")),
            to = stop(o.getJSONObject("to")),
            start = time(o.getString("startTime")),
            end = time(o.getString("endTime")),
            // MOTIS only reports distance for street legs; measure rides along their shape.
            distance = if (o.has("distance")) o.getDouble("distance") else pathLength(points),
            route = if (mode == TransitMode.Walk) null else o.text("routeShortName") ?: o.text("displayName") ?: o.text("routeLongName"),
            color = o.text("routeColor")?.let(::parseColor),
            headsign = o.text("headsign"),
            agency = o.text("agencyName"),
            stops = o.optJSONArray("intermediateStops")?.length() ?: 0,
            points = points,
            realTime = o.optBoolean("realTime"),
        )
    }

    /** The trip's own ends come back as "START" and "END". */
    private fun stop(o: JSONObject) = TransitStop(
        name = when (val name = o.optString("name")) {
            "START" -> "Start"
            "END" -> "Destination"
            else -> name
        },
        lat = o.getDouble("lat"),
        lon = o.getDouble("lon"),
    )

    private fun time(iso: String) = Instant.parse(iso).toEpochMilli()

    /** GTFS colours are 6 hex digits without '#'; returns opaque ARGB. */
    internal fun parseColor(hex: String): Int? =
        hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }

    private fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
