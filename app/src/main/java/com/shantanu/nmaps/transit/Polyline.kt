package com.shantanu.nmaps.transit

import com.shantanu.nmaps.data.LatLon

/**
 * Decodes a Google encoded polyline. [precision] is the number of decimal digits the encoder
 * kept: 5 for Google, 6 for MOTIS /api/v2+ (and OSRM's polyline6), 7 for MOTIS /api/v1.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>()
    var i = 0
    var lat = 0L
    var lon = 0L
    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = encoded[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (i < encoded.length) {
        lat += next()
        lon += next()
        out += LatLon(lat / factor, lon / factor)
    }
    return out
}
