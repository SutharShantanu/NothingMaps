package com.shantanu.nmaps.transit

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Step
import com.shantanu.nmaps.data.Travel

/**
 * Written directions, one or two per leg: "walk" for walks, "board" and "alight" around each
 * ride, then a final "arrive". Each step sits where it happens and carries the leg's distance.
 */
fun TransitItinerary.steps(): List<Step> = buildList {
    for (leg in legs) {
        if (leg.mode == TransitMode.Walk) {
            if (leg.distance < 1 && leg.from.name == leg.to.name) continue
            val text = when {
                leg.to.name == "Destination" -> "Walk to your destination"
                leg.from.name == leg.to.name && leg.distance < 100 -> "Change at ${leg.to.name}"
                else -> "Walk to ${leg.to.name}"
            }
            add(Step(text, "walk", null, leg.distance, leg.from.point))
        } else {
            add(Step(leg.boardText(), "board", null, leg.distance, leg.from.point))
            add(Step("Get off at ${leg.to.name}", "alight", null, 0.0, leg.to.point))
        }
    }
    add(Step("Arrive at your destination", "arrive", null, 0.0, legs.last().to.point))
}

/** "Take Bus 543 towards Badarpur, 5 stops" */
internal fun TransitLeg.boardText(): String {
    val rides = stops + 1
    val showMode = route == null || !(route.contains(mode.label, ignoreCase = true) || route.contains("line", ignoreCase = true))
    val name = listOfNotNull(mode.label.takeIf { showMode }, route).joinToString(" ")
    val towards = headsign?.let { " towards $it" }.orEmpty()
    return "Take $name$towards, $rides ${if (rides == 1) "stop" else "stops"}"
}

/** The itinerary as a [Route], for drawing it on the map and listing its steps. */
fun TransitItinerary.toRoute(): Route {
    val points = mutableListOf<LatLon>()
    for (leg in legs) for (p in leg.points) if (points.lastOrNull() != p) points += p
    return Route(
        travel = Travel.Transit,
        points = points,
        distance = legs.sumOf { it.distance },
        duration = durationSec.toDouble(),
        steps = steps(),
        via = rides.mapNotNull { it.route }.joinToString(" › "),
    )
}
