package com.shantanu.nmaps.data

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Place search and reverse lookup through Photon (komoot), built on OpenStreetMap data. */
object Geocoder {
    private const val BASE = "https://photon.komoot.io"

    // Photon only localises names into these languages.
    private val lang get() = Locale.getDefault().language.takeIf { it in setOf("en", "de", "fr") } ?: "default"

    suspend fun search(query: String, near: LatLon?): List<Place> {
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" }.orEmpty()
        val url = "$BASE/api/?limit=12&lang=$lang&q=${URLEncoder.encode(query, "UTF-8")}$bias"
        return parse(Http.json(url))
    }

    suspend fun reverse(at: LatLon): Place? =
        parse(Http.json("$BASE/reverse?limit=1&lang=$lang&lat=${at.lat}&lon=${at.lon}")).firstOrNull()

    private fun parse(json: JSONObject): List<Place> {
        val features = json.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            fun s(key: String) = p.optString(key).trim()
            val street = listOf(s("street"), s("housenumber")).filter { it.isNotEmpty() }.joinToString(" ")
            val town = s("city").ifEmpty { s("locality") }.ifEmpty { s("district") }
            val name = s("name").ifEmpty { street }.ifEmpty { town }.ifEmpty { return@mapNotNull null }
            val address = listOf(street, town, s("state"), s("country"))
                .filter { it.isNotEmpty() && it != name }
                .distinct()
                .joinToString(", ")
            Place(name, address, coords.getDouble(1), coords.getDouble(0))
        }.distinctBy { it.key }
    }
}
