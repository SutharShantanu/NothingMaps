package com.shantanu.nmaps.transit

import com.shantanu.nmaps.data.LatLon

enum class TransitMode(val label: String) {
    Walk("Walk"),
    Bus("Bus"),
    Metro("Metro"),
    Rail("Train"),
    Tram("Tram"),
    Ferry("Ferry"),
    Other("Transit");

    companion object {
        /** Maps a MOTIS mode string (e.g. "SUBWAY", "REGIONAL_RAIL") to the few kinds the UI shows. */
        fun of(mode: String): TransitMode = when (mode) {
            "WALK" -> Walk
            "BUS", "COACH" -> Bus
            "SUBWAY", "METRO" -> Metro
            "RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_RAIL",
            "REGIONAL_FAST_RAIL", "SUBURBAN" -> Rail
            "TRAM", "CABLE_CAR", "FUNICULAR" -> Tram
            "FERRY" -> Ferry
            else -> Other
        }
    }
}

data class TransitStop(val name: String, val lat: Double, val lon: Double) {
    val point get() = LatLon(lat, lon)
}

/** One walk or ride. Times are epoch millis; [distance] is metres along [points]. */
data class TransitLeg(
    val mode: TransitMode,
    val from: TransitStop,
    val to: TransitStop,
    val start: Long,
    val end: Long,
    val distance: Double,
    /** Short name such as "Yellow Line", "S5" or "543"; null for walks. */
    val route: String?,
    /** Route colour as ARGB, if the feed provides one. */
    val color: Int?,
    val headsign: String?,
    val agency: String?,
    /** Stops passed between boarding and getting off. */
    val stops: Int,
    val points: List<LatLon>,
    val realTime: Boolean,
) {
    val durationSec get() = (end - start) / 1000
}

data class TransitItinerary(
    val start: Long,
    val end: Long,
    val durationSec: Long,
    val transfers: Int,
    val walkMeters: Double,
    val legs: List<TransitLeg>,
) {
    /** Legs that ride a vehicle, i.e. everything but walks. */
    val rides get() = legs.filter { it.mode != TransitMode.Walk }
}
