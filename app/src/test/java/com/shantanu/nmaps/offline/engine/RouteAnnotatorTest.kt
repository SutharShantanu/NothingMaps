package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Step
import com.shantanu.nmaps.data.Structure
import com.shantanu.nmaps.data.Travel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAnnotatorTest {
    private fun tiles(): TileSource {
        val dir = java.io.File(javaClass.getResource("/tiles")!!.toURI())
        return MemoryTileSource(dir.listFiles()!!.associate { f ->
            val (z, x, y) = f.nameWithoutExtension.split("-").map { it.toInt() }
            TileId(z, x, y) to f.readBytes()
        })
    }

    private fun route(fixture: String): Route {
        val r = JSONObject(javaClass.getResource(fixture)!!.readText()).getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coords.length()).map { LatLon(coords.getJSONArray(it).getDouble(1), coords.getJSONArray(it).getDouble(0)) }
        val steps = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { s ->
                val m = s.getJSONObject("maneuver"); val loc = m.getJSONArray("location")
                Step("x", m.getString("type"), m.optString("modifier").ifEmpty { null }, s.getDouble("distance"), LatLon(loc.getDouble(1), loc.getDouble(0)), road = s.optString("name"))
            }
        }
        return Route(Travel.Drive, points, r.getDouble("distance"), r.getDouble("duration"), steps)
    }

    @Test fun serviceRoadBesideUnderpassGetsNoUnderpassStep() {
        // Recorded OSRM drive in Sector 53 that forks onto the service road beside the underpass.
        val before = route("/osrm_sector53.json")
        val after = RouteAnnotator(tiles()).annotate(before)
        assertTrue(after.steps.none { it.structure != null })
        assertTrue(after.steps.size == before.steps.size)
    }

    @Test fun straightThroughUnderpassGetsAStep() {
        // Recorded OSRM drive straight down Golf Course Road through the Genpact Circle underpass.
        val after = RouteAnnotator(tiles()).annotate(route("/osrm_underpass.json"))
        after.steps.forEach { println("ANNOT ${it.type} ${it.structure} | ${it.instruction} @ ${it.at}") }
        val entry = after.steps.single { it.structure == Structure.Underpass }
        assertTrue(entry.instruction, entry.instruction == "Continue to take the underpass")
        // It sits near the tunnel's south end (28.4489, 77.0997), between depart and the next turn.
        assertTrue(kotlin.math.abs(entry.at.lat - 28.4489) < 0.0015)
        assertTrue(after.steps.indexOf(entry) == 1)
    }

    @Test fun brunnelValuesInTiles() {
        val src = tiles()
        val counts = HashMap<String, Int>()
        src.tiles(14).forEach { id ->
            MvtTile.decode(src.tile(id)!!).layer("transportation")?.features?.forEach { f ->
                val k = "${f.string("class")}/${f.get("brunnel")}/toll=${f.get("toll")}"
                if (f.get("brunnel") == "tunnel" && f.string("class") == "secondary") {
                    val g = f.geometry.first()
                    val a = tileToLatLon(id.z, id.x, id.y, 4096, g[0].toDouble(), g[1].toDouble())
                    val b = tileToLatLon(id.z, id.x, id.y, 4096, g[g.size - 2].toDouble(), g[g.size - 1].toDouble())
                    println("TUNNEL ${a.lat},${a.lon} -> ${b.lat},${b.lon}")
                }
                if (f.get("brunnel") != null || f.get("toll") != null) counts[k] = (counts[k] ?: 0) + 1
            }
        }
        counts.forEach { (k, v) -> println("BRUNNEL $v $k") }
    }
}
