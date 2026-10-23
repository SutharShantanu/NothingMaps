package com.shantanu.nmaps.data

import com.shantanu.nmaps.share.ShareLink
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * The app's own share links. They point at a small page on GitHub Pages that opens the app when
 * it's installed and otherwise sends the person to the latest release to download it.
 */
object AppLinks {
    const val HOST = "sutharshantanu.github.io"
    const val PATH = "/NothingMaps/"
    const val BASE = "https://$HOST$PATH"
    const val RELEASES = "https://github.com/SutharShantanu/NothingMaps/releases/latest"

    sealed interface Target {
        data class Place(val at: LatLon, val name: String?) : Target
        data class Live(val link: ShareLink) : Target
    }

    fun place(lat: Double, lon: Double, name: String? = null): String =
        BASE + "?at=" + "%.5f,%.5f".format(Locale.US, lat, lon) + (name?.takeIf { it.isNotBlank() }?.let { "&n=" + encode(it) }.orEmpty())

    fun live(link: ShareLink): String = BASE + "?live=" + link.topic + "/" + com.shantanu.nmaps.share.ShareCrypto.encode(link.key)

    /** Reads a web link or an `nmaps://open?…` deep link; null for anything else. */
    fun parse(url: String): Target? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { p ->
            val k = p.substringBefore('=')
            val v = p.substringAfter('=', "")
            if (k.isEmpty()) null else k to decode(v)
        }.toMap()
        params["live"]?.let { ShareLink.find("nmaps://live/$it") }?.let { return Target.Live(it) }
        params["at"]?.let(::parseCoordinates)?.let { return Target.Place(it, params["n"]?.takeIf { n -> n.isNotBlank() }) }
        return null
    }

    fun isOurs(url: String) = url.contains(HOST + PATH) || url.startsWith("nmaps://open")

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
