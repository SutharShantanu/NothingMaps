package com.shantanu.nmaps.data

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Nearby categories. [tags] query OpenStreetMap through Overpass; [subclasses] match the same
 * places in the map's own vector tiles, which works instantly and offline.
 */
enum class Category(val label: String, val tags: List<String>, val subclasses: List<String>) {
    Food("Food", listOf("amenity~\"^(restaurant|cafe|fast_food|food_court)$\""), listOf("restaurant", "cafe", "fast_food", "food_court")),
    Fuel("Fuel", listOf("amenity=fuel"), listOf("fuel")),
    Charging("EV charging", listOf("amenity=charging_station"), listOf("charging_station")),
    Atm("ATM", listOf("amenity=atm", "amenity=bank"), listOf("atm", "bank")),
    Pharmacy("Pharmacy", listOf("amenity=pharmacy", "shop=chemist"), listOf("pharmacy", "chemist")),
    Hospital("Hospital", listOf("amenity~\"^(hospital|clinic)$\""), listOf("hospital", "clinic", "doctors")),
    Parking("Parking", listOf("amenity=parking"), listOf("parking")),
    Toilets("Toilets", listOf("amenity=toilets"), listOf("toilets")),
    Grocery("Groceries", listOf("shop~\"^(supermarket|convenience|greengrocer)$\""), listOf("supermarket", "convenience", "greengrocer")),
}

object Overpass {
    // The main server is often at capacity; public mirrors run the same data.
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private suspend fun query(q: String): JSONObject {
        var last: Exception? = null
        for (url in servers) {
            try {
                val body = Http.post(url, "data=" + URLEncoder.encode(q, "UTF-8"), mapOf("Content-Type" to "application/x-www-form-urlencoded"), readTimeoutMs = 12_000)
                return JSONObject(body)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: java.io.IOException("No Overpass server answered")
    }

    /** Named places of [category] within [radius] metres, nearest first is left to the caller. */
    suspend fun nearby(category: Category, at: LatLon, radius: Int = 3000): List<Place> {
        val parts = category.tags.joinToString("") { "nwr[$it](around:$radius,${at.lat},${at.lon});" }
        val json = query("[out:json][timeout:20];($parts);out center tags 60;")
        val els = json.optJSONArray("elements") ?: return emptyList()
        return (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val lat = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.getDouble("lat") ?: return@mapNotNull null
            val lon = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.getDouble("lon") ?: return@mapNotNull null
            val name = tags.optString("name:en").ifEmpty { tags.optString("name") }.ifEmpty { tags.optString("brand") }.ifEmpty { category.label }
            val address = listOf(
                listOf(tags.optString("addr:housenumber"), tags.optString("addr:street")).filter { it.isNotBlank() }.joinToString(" "),
                tags.optString("addr:city"),
                tags.optString("opening_hours").takeIf { it.isNotBlank() }?.let { "Hours: $it" }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(", ")
            Place(name, address, lat, lon)
        }.distinctBy { it.key }
    }

    /**
     * The posted limit on the road at [at], in km/h. OSM tags it as "50", "30 mph" or by zone,
     * e.g. "IN:urban"; zones without a number are skipped rather than guessed.
     */
    suspend fun speedLimit(at: LatLon): Int? {
        val json = query("[out:json][timeout:10];way(around:20,${at.lat},${at.lon})[highway][maxspeed];out tags 3;")
        val els = json.optJSONArray("elements") ?: return null
        for (i in 0 until els.length()) {
            parseMaxspeed(els.getJSONObject(i).optJSONObject("tags")?.optString("maxspeed").orEmpty())?.let { return it }
        }
        return null
    }

    /** Every road with a posted limit within 20 m of the route, in one query. */
    suspend fun speedLimitsAlong(route: List<LatLon>): SpeedLimitMap {
        // A point every ~150 m traces the route closely enough for a 20 m corridor.
        val line = Terrain.resample(route, 150.0, 220)
        val coords = line.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lon) }
        val json = query("[out:json][timeout:25];way(around:20,$coords)[highway][maxspeed];out tags geom;")
        val els = json.optJSONArray("elements") ?: return SpeedLimitMap(emptyList())
        return SpeedLimitMap((0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i)
            val limit = parseMaxspeed(e.optJSONObject("tags")?.optString("maxspeed").orEmpty()) ?: return@mapNotNull null
            val geom = e.optJSONArray("geometry") ?: return@mapNotNull null
            (0 until geom.length()).map { k -> geom.getJSONObject(k).let { LatLon(it.getDouble("lat"), it.getDouble("lon")) } } to limit
        })
    }

    fun parseMaxspeed(raw: String): Int? {
        val m = Regex("""^\s*(\d+)\s*(mph)?""").find(raw) ?: return null
        val v = m.groupValues[1].toIntOrNull() ?: return null
        return if (m.groupValues[2] == "mph") (v * 1.609344).roundToInt() else v
    }
}

/**
 * Reads coordinates typed into search: "28.61, 77.20", "28.61 77.20", "-33.86,151.21",
 * or degrees with hemispheres like "28.61N 77.20E".
 */
fun parseCoordinates(text: String): LatLon? {
    val t = text.trim().uppercase(Locale.US)
    Regex("""^(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)$""").find(t)?.let {
        val lat = it.groupValues[1].toDouble()
        val lon = it.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }
    Regex("""^(\d{1,2}(?:\.\d+)?)\s*°?\s*([NS])[, ]*\s*(\d{1,3}(?:\.\d+)?)\s*°?\s*([EW])$""").find(t)?.let {
        val lat = it.groupValues[1].toDouble() * if (it.groupValues[2] == "S") -1 else 1
        val lon = it.groupValues[3].toDouble() * if (it.groupValues[4] == "W") -1 else 1
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }
    return null
}
