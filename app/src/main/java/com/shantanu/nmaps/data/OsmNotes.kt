package com.shantanu.nmaps.data

import com.shantanu.nmaps.BuildConfig
import org.json.JSONObject
import java.net.URLEncoder

enum class Problem(val label: String, val prefix: String) {
    MissingPlace("Missing place", "Missing place"),
    WrongInfo("Wrong name or details", "Wrong details"),
    Road("Road problem", "Road problem"),
    Other("Something else", "Map problem"),
}

/**
 * Map problems go to OpenStreetMap as public notes, which volunteer mappers review and fix.
 * Anonymous notes need no account.
 */
object OsmNotes {
    private val api = BuildConfig.OSM_API

    data class Sent(val id: Long, val url: String)

    suspend fun send(at: LatLon, problem: Problem, details: String, placeName: String?): Sent {
        val text = buildString {
            append("${problem.prefix}")
            if (!placeName.isNullOrBlank()) append(" at \"$placeName\"")
            append(": ${details.trim()}")
            append("\n\n(Reported from a personal maps app)")
        }
        val body = listOf("lat" to "${at.lat}", "lon" to "${at.lon}", "text" to text)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val json = JSONObject(Http.post("$api/api/0.6/notes.json", body, mapOf("Content-Type" to "application/x-www-form-urlencoded")))
        val id = json.getJSONObject("properties").getLong("id")
        val site = api.replace("api.openstreetmap.org", "www.openstreetmap.org")
        return Sent(id, "$site/note/$id")
    }
}
