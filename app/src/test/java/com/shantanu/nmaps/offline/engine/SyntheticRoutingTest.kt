package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.data.Travel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built tiles: a block of minor roads where one side is one-way. */
class SyntheticRoutingTest {
    private val id = TileId(14, 11701, 6840)
    private val minor = mapOf("class" to "minor")
    private val oneway = mapOf("class" to "minor", "oneway" to 1L)

    private fun at(px: Int, py: Int, tile: TileId = id) = tileToLatLon(14, tile.x, tile.y, 4096, px.toDouble(), py.toDouble())

    private fun router(vararg tiles: Pair<TileId, List<MvtWriter.Feature>>) =
        OfflineRouter(MemoryTileSource(tiles.associate { (t, f) -> t to MvtWriter().layer("transportation", f).build() }))

    // A(1000,1000) B(2000,1000) C(2000,2000) D(1000,2000); A–B may only be driven from B to A.
    private val block = listOf(
        line(oneway, 2000, 1000, 1500, 1000, 1000, 1000),
        line(minor, 2000, 1000, 2000, 2000),
        line(minor, 2000, 2000, 1000, 2000),
        line(minor, 1000, 2000, 1000, 1000),
        // Dead-end spurs make each corner a junction.
        line(minor, 1000, 1000, 700, 700),
        line(minor, 2000, 1000, 2300, 700),
        line(minor, 2000, 2000, 2300, 2300),
        line(minor, 1000, 2000, 700, 2300),
    )

    @Test fun onewayIsRespectedWhenDriving() {
        val r = router(id to block)
        val from = at(1100, 1000)
        val to = at(1900, 1000)
        val side = metersPerUnit(id.y * 4096.0 + 1500) // metres per grid unit
        val drive = r.route(listOf(from, to), Travel.Drive)
        val walk = r.route(listOf(from, to), Travel.Walk)
        assertEquals(800 * side, walk.distance, 3 * side)
        // Around the block: back to A, down, across, up to B, along to the destination.
        assertEquals((100 + 1000 + 1000 + 1000 + 100) * side, drive.distance, 5 * side)
        assertTrue(drive.steps.count { it.type == "turn" } >= 3)
        assertTrue(drive.steps.filter { it.type == "turn" }.all { it.modifier == "left" })
        // Driving with the one-way is direct.
        assertEquals(800 * side, r.route(listOf(to, from), Travel.Drive).distance, 3 * side)
    }

    @Test fun avoidsTollsMotorwaysAndFerries() {
        val around = listOf(
            line(minor, 2000, 1000, 2000, 2000),
            line(minor, 2000, 2000, 1000, 2000),
            line(minor, 1000, 2000, 1000, 1000),
        )
        val side = metersPerUnit(id.y * 4096.0 + 1500)
        val from = at(1000, 1000)
        val to = at(2000, 1000)
        for ((avoid, tags) in listOf(
            "toll" to mapOf("class" to "minor", "toll" to 1L),
            "motorway" to mapOf("class" to "motorway", "ramp" to 1L),
            "ferry" to mapOf("class" to "ferry"),
        )) {
            val r = router(id to around + line(tags, 1000, 1000, 2000, 1000))
            val travel = if (avoid == "motorway") Travel.Drive else Travel.Walk
            assertEquals(avoid, 1000 * side, r.route(listOf(from, to), travel).distance, 10 * side)
            assertEquals(avoid, 3000 * side, r.route(listOf(from, to), travel, avoid = setOf(avoid)).distance, 10 * side)
        }
    }

    @Test fun joinsDeadEndsThatDriftedOffTheRoad() {
        // A side road ending 4 units short of the main road (simplification drift) still joins it,
        // but a parallel road 20 units away does not.
        val r = router(id to listOf(
            line(minor, 1000, 1000, 3000, 1000),
            line(minor, 2000, 2000, 2000, 1004),
            line(minor, 1000, 1020, 3000, 1020),
        ))
        val route = r.route(listOf(at(2000, 1900), at(2900, 1000)), Travel.Walk)
        assertEquals(1800 * metersPerUnit(id.y * 4096.0 + 1500), route.distance, 15.0)
        assertThrows(OfflineRouteException::class.java) { r.route(listOf(at(1100, 1020), at(1100, 1000)), Travel.Walk) }
    }

    @Test fun crossingWithoutSharedVertexIsNotAJunction() {
        // A path from the north crosses A–B at (1500, 1000) as a bridge would, without a shared vertex.
        val bridge = line(mapOf("class" to "minor", "brunnel" to "bridge"), 1400, 500, 1600, 1500)
        val north = at(1400, 520)
        val e = assertThrows(OfflineRouteException::class.java) {
            router(id to block + bridge).route(listOf(north, at(1000, 1500)), Travel.Walk)
        }
        assertEquals("No offline route between these places", e.message)
        // Sharing the vertex makes it a junction.
        val joined = line(minor, 1500, 500, 1500, 1000)
        val r = router(id to block + joined).route(listOf(at(1500, 520), at(1000, 1500)), Travel.Walk)
        assertTrue(r.steps.any { it.type == "turn" })
    }

    @Test fun joinsLinesAcrossTileEdges() {
        // One slanted road from (3000, 2000) in the west tile to (1000, 2100) in the east tile, each
        // clipped at its 64-unit buffer, so the cut points differ slightly.
        val east = TileId(14, 11702, 6840)
        val west = line(minor, 3000, 2000, 4160, 2055)
        val eastPart = line(minor, -64, 2049, 1000, 2100)
        val r = router(id to listOf(west), east to listOf(eastPart))
        val route = r.route(listOf(at(3100, 2005), at(900, 2095, east)), Travel.Drive)
        assertTrue(route.distance > 0)
        assertEquals(2, route.steps.size)
    }

    @Test fun snapsToTheNearestUsableRoad() {
        val footway = line(mapOf("class" to "path", "subclass" to "footway"), 1000, 900, 2000, 900)
        val r = router(id to block + footway)
        val from = at(1500, 905) // next to the footway, 95 units from the road
        val walk = r.route(listOf(from, at(1600, 905)), Travel.Walk)
        val drive = r.route(listOf(from, at(1600, 905)), Travel.Drive)
        assertTrue(walk.distance < drive.distance)
        assertTrue(distanceMeters(walk.points.first(), from) < 5)
    }
}
