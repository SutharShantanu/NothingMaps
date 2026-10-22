package com.shantanu.nmaps.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    /**
     * Photon, Overpass, Transitous and the OSM servers ask clients to identify themselves with a way
     * to reach the developer.
     */
    const val USER_AGENT = "NothingStyleMaps/1.0 (Android; personal use; +https://github.com/SutharShantanu)"

    suspend fun json(url: String): JSONObject {
        val body = request(url, null, emptyMap(), acceptErrorBody = true)
        return JSONObject(body)
    }

    suspend fun text(url: String): String = request(url, null, emptyMap(), acceptErrorBody = false)

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 25_000): String =
        request(url, body, headers, acceptErrorBody = false, readTimeoutMs)

    private suspend fun request(url: String, body: String?, headers: Map<String, String>, acceptErrorBody: Boolean, readTimeoutMs: Int = 25_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // OSRM answers "no route" with a 400 and a JSON body, so hand that back to the caller.
                if (code !in 200..299 && !(acceptErrorBody && text.trimStart().startsWith("{"))) {
                    throw IOException(if (code == 429) "Too many requests, try again in a minute" else "Server returned $code")
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}
