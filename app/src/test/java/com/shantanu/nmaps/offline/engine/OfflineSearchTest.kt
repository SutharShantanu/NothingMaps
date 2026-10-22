package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.distanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSearchTest {
    private val search = OfflineSearch(Fixtures.source)
    private val metro = LatLon(28.4464, 77.1001)

    @Test fun findsPoi() {
        val t = System.nanoTime()
        val hits = search.search("sector 53/54 metro", metro)
        println("Search index over ${Fixtures.ids.size} tiles + first query: ${(System.nanoTime() - t) / 1_000_000} ms")
        val top = hits.first()
        assertEquals("Sector 53/54 Metro Station", top.name)
        assertTrue(top.address, top.address.startsWith("Subway"))
        assertTrue(distanceMeters(top.point, metro) < 50)
    }

    @Test fun findsStreetAndRanksExactFirst() {
        val hits = search.search("Golf Course Road", metro)
        assertEquals("Golf Course Road", hits.first().name)
        assertTrue(hits.first().address.startsWith("Street"))
        // The same street repeats per tile, but never twice within 150 m.
        hits.filter { it.name == "Golf Course Road" }.let { same ->
            same.forEachIndexed { i, a -> same.drop(i + 1).forEach { b -> assertTrue(distanceMeters(a.point, b.point) >= 150) } }
        }
    }

    @Test fun prefixAndAccentInsensitive() {
        assertTrue(search.search("páras hosp").any { it.name == "Paras Hospital, Gurgaon" })
        assertTrue(search.search("PIZZA").any { it.name == "Pizza Hut" })
        // Word-prefix matches outrank substring and category matches.
        val hits = search.search("park", metro, limit = 200)
        val prefix = hits.map { h -> h.name.lowercase().split(Regex("[^a-z0-9]+")).any { it.startsWith("park") } }
        assertTrue(prefix.first())
        assertTrue(prefix.lastIndexOf(true) < prefix.indexOf(false).let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue(search.search("zzzz-not-here").isEmpty())
    }

    @Test fun nearestFirstAndLocality() {
        val hits = search.search("hdfc bank", LatLon(28.4220, 77.0910))
        assertEquals("HDFC Bank", hits.first().name)
        assertTrue(distanceMeters(hits.first().point, LatLon(28.421948, 77.091016)) < 30)
        assertTrue(hits.first().address, hits.first().address.startsWith("Bank, "))
    }

    @Test fun emptySourceAndInvalidate() {
        val map = HashMap<TileId, ByteArray>()
        val s = OfflineSearch(object : TileSource {
            override fun tiles(z: Int) = map.keys.filter { it.z == z }
            override fun tile(id: TileId) = map[id]
        })
        assertTrue(s.search("pizza").isEmpty())
        Fixtures.ids.forEach { map[it] = Fixtures.bytes(it) }
        assertTrue(s.search("pizza").isEmpty())
        s.invalidate()
        assertTrue(s.search("pizza").isNotEmpty())
    }
}
