package com.shantanu.nmaps.data

import org.json.JSONObject
import java.io.IOException

/** Turn-by-turn routes from the FOSSGIS OSRM servers (car, bike and foot profiles). */
object Router {
    suspend fun route(points: List<LatLon>, travel: Travel): Route = routes(points, travel, alternatives = false).first()

    /**
     * Route through [points] in order (start, any stops, destination). The best route comes first,
     * then up to two alternatives, like Google Maps' grey options; OSRM only offers those without stops.
     */
    suspend fun routes(points: List<LatLon>, travel: Travel, alternatives: Boolean = points.size == 2): List<Route> {
        require(points.size >= 2)
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val url = "https://routing.openstreetmap.de/${travel.profile}/route/v1/driving/" +
            "$coords?overview=full&geometries=geojson&steps=true" +
            if (alternatives) "&alternatives=2" else ""
        val json = Http.json(url)
        if (json.optString("code") != "Ok") {
            throw IOException(
                when (json.optString("code")) {
                    "NoRoute" -> "No ${travel.label.lowercase()} route between these places"
                    "NoSegment" -> "One of these places is too far from a road"
                    else -> json.optString("message").ifEmpty { "Couldn't get a route" }
                }
            )
        }
        val arr = json.getJSONArray("routes")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it), travel) }
    }

    private fun parse(r: JSONObject, travel: Travel): Route {
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            LatLon(c.getDouble(1), c.getDouble(0))
        }
        val steps = mutableListOf<Step>()
        val legs = r.getJSONArray("legs")
        val legEnds = mutableListOf<Int>()
        val classes = mutableSetOf<String>()
        var via = ""
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            if (via.isEmpty()) via = leg.optString("summary")
            val arr = leg.getJSONArray("steps")
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.getString("type")
                val modifier = m.optString("modifier").ifEmpty { null }
                val exit = if (m.has("exit")) m.getInt("exit") else null
                val name = s.optString("name")
                val intersections = s.optJSONArray("intersections")
                val stepClasses = (0 until (intersections?.length() ?: 0)).flatMap { k ->
                    val c = intersections!!.getJSONObject(k).optJSONArray("classes")
                    (0 until (c?.length() ?: 0)).map { c!!.getString(it) }
                }.toSet()
                classes += stepClasses
                val structure = Instructions.structure(name, stepClasses)
                // Each leg but the last ends at a stop, not the destination.
                val stopArrival = type == "arrive" && l < legs.length() - 1
                steps += Step(
                    instruction = if (stopArrival) "Arrive at stop ${l + 1}" else Instructions.text(type, modifier, name, exit, structure),
                    type = type,
                    modifier = modifier,
                    distance = s.getDouble("distance"),
                    at = LatLon(loc.getDouble(1), loc.getDouble(0)),
                    // Lanes at the manoeuvre are on the step's first intersection.
                    lanes = intersections?.optJSONObject(0)?.optJSONArray("lanes")?.let { arr ->
                        (0 until arr.length()).map { k ->
                            val lane = arr.getJSONObject(k)
                            val ind = lane.optJSONArray("indications")
                            Lane((0 until (ind?.length() ?: 0)).map { ind!!.getString(it) }, lane.optBoolean("valid"))
                        }
                    }.orEmpty(),
                    structure = structure,
                    road = name,
                )
            }
            legEnds += steps.lastIndex
        }
        // Without a server summary, name the longest named road on the route.
        if (via.isEmpty()) {
            via = (0 until legs.length()).flatMap { l ->
                val arr = legs.getJSONObject(l).getJSONArray("steps")
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }.filter { it.optString("name").isNotBlank() }.maxByOrNull { it.getDouble("distance") }?.getString("name").orEmpty()
        }
        return Route(travel, points, r.getDouble("distance"), r.getDouble("duration"), steps, via, legEnds = legEnds, classes = classes)
    }

    /** Treats a GPX track as a route to follow: no turn instructions, just the line and its end. */
    fun fromTrack(points: List<LatLon>, name: String, travel: Travel, seconds: Double): Route {
        val distance = com.shantanu.nmaps.nav.pathLength(points)
        val steps = listOf(
            Step("Follow $name", "depart", null, distance, points.first()),
            Step("Arrive at the end of $name", "arrive", null, 0.0, points.last()),
        )
        return Route(travel, points, distance, seconds, steps, name, isTrack = true)
    }
}

object Instructions {
    private val flyoverWords = Regex("""(?i)\b(flyover|fly over|overbridge|over bridge|elevated|skyway|viaduct)\b""")
    private val underpassWords = Regex("""(?i)\b(underpass|under pass|subway|tunnel)\b""")

    /**
     * Whether a step's road is a flyover or underpass. OSRM marks tunnels; flyovers it doesn't mark,
     * but they're almost always named so in India and the UK ("… Flyover", "… Elevated Road").
     */
    fun structure(name: String, classes: Set<String>): Structure? = when {
        "tunnel" in classes || underpassWords.containsMatchIn(name) -> Structure.Underpass
        flyoverWords.containsMatchIn(name) -> Structure.Flyover
        else -> null
    }

    /** "Use the left 2 lanes" when only some lanes lead the right way; null when any lane will do. */
    fun laneHint(lanes: List<Lane>): String? {
        val valid = lanes.indices.filter { lanes[it].valid }
        if (lanes.size < 2 || valid.isEmpty() || valid.size == lanes.size) return null
        val count = if (valid.size == 1) "lane" else "${valid.size} lanes"
        return when {
            valid.first() == 0 -> "Use the left $count"
            valid.last() == lanes.lastIndex -> "Use the right $count"
            else -> "Use the middle $count"
        }
    }

    fun text(type: String, modifier: String?, name: String, exit: Int?, structure: Structure? = null): String {
        val base = baseText(type, modifier, name, exit)
        // Say it the way people do: "Keep left to take the flyover", "Continue into the underpass".
        if (structure == null || type in setOf("depart", "arrive", "roundabout", "rotary", "exit roundabout", "exit rotary")) return base
        val what = if (structure == Structure.Flyover) "the flyover" else "the underpass"
        return when {
            name.isBlank() -> "$base to take $what"
            // The road's name already says it, e.g. "onto Golf Course Road Underpass".
            flyoverWords.containsMatchIn(name) || underpassWords.containsMatchIn(name) -> base
            else -> "$base, through $what"
        }
    }

    private fun baseText(type: String, modifier: String?, name: String, exit: Int?): String {
        val onto = if (name.isNotBlank()) " onto $name" else ""
        val dir = when (modifier) {
            "slight left" -> "slightly left"
            "slight right" -> "slightly right"
            null -> ""
            else -> modifier
        }
        return when (type) {
            "depart" -> if (name.isNotBlank()) "Head along $name" else "Head out"
            "arrive" -> "Arrive at your destination"
            "turn", "end of road" -> when (modifier) {
                "uturn" -> "Make a U-turn$onto"
                "straight", null -> "Continue straight$onto"
                else -> "Turn $dir$onto"
            }
            "new name", "continue" -> when (modifier) {
                "uturn" -> "Make a U-turn$onto"
                "straight", null -> "Continue$onto"
                else -> "Keep $dir$onto"
            }
            "merge" -> "Merge $dir$onto".replace("  ", " ").trim()
            "on ramp" -> "Take the ramp$onto"
            "off ramp" -> "Take the exit$onto"
            "fork" -> "Keep ${if (modifier?.contains("left") == true) "left" else "right"} at the fork$onto"
            "roundabout", "rotary" ->
                if (exit != null) "At the roundabout, take the ${ordinal(exit)} exit$onto" else "Enter the roundabout"
            "roundabout turn" -> "At the roundabout, turn $dir$onto"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
            else -> if (name.isNotBlank()) "Continue on $name" else "Continue"
        }
    }

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
