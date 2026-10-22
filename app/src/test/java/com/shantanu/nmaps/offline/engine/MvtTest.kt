package com.shantanu.nmaps.offline.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MvtTest {
    @Test fun decodesRealTile() {
        val tile = MvtTile.decode(Fixtures.bytes(TileId(14, 11700, 6840)))
        val names = tile.layers.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("transportation", "transportation_name", "poi", "place", "water_name", "housenumber")))
        val poi = tile.layer("poi")!!
        assertEquals(4096, poi.extent)
        val metro = poi.features.first { it["name"] == "Sector 53/54 Metro Station" }
        assertEquals(GeomType.Point, metro.type)
        assertEquals("railway", metro.tags["class"])
        assertEquals("subway", metro.string("subclass"))
        assertEquals(3L, metro["rank"])
        val (x, y) = metro.geometry[0].let { it[0] to it[1] }
        val at = tileToLatLon(14, 11700, 6840, poi.extent, x.toDouble(), y.toDouble())
        assertEquals(28.4464, at.lat, 2e-4)
        assertEquals(77.1001, at.lon, 2e-4)

        val roads = tile.layer("transportation")!!.features
        assertTrue(roads.any { it["oneway"] == 1L })
        assertTrue(roads.any { it["brunnel"] == "bridge" })
        assertTrue(roads.all { f -> f.geometry.all { p -> p.all { it in -64..4160 } } })
        val golf = tile.layer("transportation_name")!!.features.first { it["name"] == "Golf Course Road" }
        assertEquals(GeomType.Line, golf.type)
        assertTrue(golf.geometry.sumOf { it.size } >= 4)
    }

    @Test fun roundTripsEveryValueAndCommand() {
        val bytes = MvtWriter()
            .layer("things", listOf(
                MvtWriter.Feature(GeomType.Point, mapOf("s" to "café", "f" to 1.5f, "d" to -2.25, "i" to 7, "u" to 9_000_000_000L, "neg" to -5L, "b" to true), listOf(intArrayOf(10, 20, -30, 4100)), id = 42),
                MvtWriter.Feature(GeomType.Line, mapOf("s" to "x"), listOf(intArrayOf(0, 0, 100, -50, 300, 200), intArrayOf(5, 5, 6, 7))),
                MvtWriter.Feature(GeomType.Polygon, emptyMap(), listOf(intArrayOf(0, 0, 10, 0, 10, 10), intArrayOf(2, 2, 3, 2, 3, 3))),
            ), extent = 512)
            .layer("empty", emptyList())
            .build()
        val tile = MvtTile.decode(bytes)
        assertEquals(listOf("things", "empty"), tile.layers.map { it.name })
        val l = tile.layer("things")!!
        assertEquals(512, l.extent)
        val p = l.features[0]
        assertEquals(42L, p.id)
        assertEquals(mapOf("s" to "café", "f" to 1.5, "d" to -2.25, "i" to 7L, "u" to 9_000_000_000L, "neg" to -5L, "b" to true), p.tags)
        assertEquals(listOf(10, 20, -30, 4100), p.geometry.single().toList())
        assertEquals(listOf(listOf(0, 0, 100, -50, 300, 200), listOf(5, 5, 6, 7)), l.features[1].geometry.map { it.toList() })
        assertEquals(2, l.features[2].geometry.size)
        assertEquals(listOf(2, 2, 3, 2, 3, 3), l.features[2].geometry[1].toList())
        assertNotNull(tile.layer("empty"))
    }

    @Test fun tileCorners() {
        val nw = tileToLatLon(14, 11701, 6840, 4096, 0.0, 0.0)
        assertEquals(28.459033, nw.lat, 1e-6)
        assertEquals(77.102051, nw.lon, 1e-6)
        val se = tileToLatLon(14, 11701, 6840, 4096, 4096.0, 4096.0)
        assertEquals(28.439714, se.lat, 1e-6)
        assertEquals(77.124023, se.lon, 1e-6)
    }
}
