package com.shantanu.nmaps.data

import org.json.JSONObject

data class LatLon(val lat: Double, val lon: Double)

data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
) {
    val point get() = LatLon(lat, lon)

    /** Places are identified by position; two results within ~1 m are the same place. */
    val key get() = "%.5f,%.5f".format(java.util.Locale.US, lat, lon)

    fun toJson() = JSONObject().put("name", name).put("address", address).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = Place(o.getString("name"), o.optString("address"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

/** A location fix from the device. [bearing] is only set while moving. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float?,
    val altitude: Double?,
    val time: Long,
) {
    val point get() = LatLon(lat, lon)
}

enum class Travel(val label: String, val profile: String) {
    Drive("Drive", "routed-car"),
    Cycle("Cycle", "routed-bike"),
    Walk("Walk", "routed-foot"),
    /** Planned by Transitous rather than OSRM, so it has no routing profile. */
    Transit("Transit", ""),
}

/** One lane at a junction: the arrows painted on it, and whether it leads where the route goes. */
data class Lane(val indications: List<String>, val valid: Boolean)

/** Grade-separated roads the step uses — what makes "take the flyover" different from the road beside it. */
enum class Structure { Flyover, Underpass }

data class Step(
    val instruction: String,
    val type: String,
    val modifier: String?,
    val distance: Double,
    val at: LatLon,
    /** Lane layout approaching this manoeuvre, where OpenStreetMap has it. */
    val lanes: List<Lane> = emptyList(),
    val structure: Structure? = null,
    val road: String = "",
)

data class Route(
    val travel: Travel,
    val points: List<LatLon>,
    val distance: Double,
    val duration: Double,
    val steps: List<Step>,
    /** Main road, e.g. "via NH 48", to tell alternatives apart. */
    val via: String = "",
    /** Following an imported GPX track rather than a computed route. */
    val isTrack: Boolean = false,
    /** Index in [steps] of each leg's final "arrive" step; one leg per stop plus the last. */
    val legEnds: List<Int> = emptyList(),
    /** Road types the route uses, as OSRM reports them: "toll", "motorway", "ferry". */
    val classes: Set<String> = emptySet(),
)
