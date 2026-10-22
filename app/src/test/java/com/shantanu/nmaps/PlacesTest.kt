package com.shantanu.nmaps

import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Overpass
import com.shantanu.nmaps.data.parseCoordinates
import com.shantanu.nmaps.offline.Bounds
import com.shantanu.nmaps.offline.OfflineMaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesTest {
    @Test fun coordinates() {
        assertEquals(LatLon(28.61, 77.2), parseCoordinates("28.61, 77.2"))
        assertEquals(LatLon(28.61, 77.2), parseCoordinates("28.61 77.2"))
        assertEquals(LatLon(-33.86, 151.21), parseCoordinates("-33.86,151.21"))
        assertEquals(LatLon(28.61, -77.2), parseCoordinates("28.61N 77.2W"))
        assertNull(parseCoordinates("95, 10"))
        assertNull(parseCoordinates("Cyber hub"))
    }

    @Test fun maxspeed() {
        assertEquals(50, Overpass.parseMaxspeed("50"))
        assertEquals(48, Overpass.parseMaxspeed("30 mph"))
        assertNull(Overpass.parseMaxspeed("IN:urban"))
        assertNull(Overpass.parseMaxspeed("none"))
    }

    @Test fun offlineTileCount() {
        // A point-sized area still needs one tile per zoom level.
        assertEquals(15L, OfflineMaps.tileCount(Bounds(28.4, 77.0, 28.4, 77.0)))
        // About a 10 km city block: a few hundred tiles, well under the limit.
        val city = OfflineMaps.tileCount(Bounds(28.40, 77.00, 28.49, 77.10))
        assert(city in 50..400) { "got $city" }
    }
}

class InstructionsTest {
    @org.junit.Test fun structures() {
        val i = com.shantanu.nmaps.data.Instructions
        org.junit.Assert.assertEquals(com.shantanu.nmaps.data.Structure.Underpass, i.structure("Golf Course Road Underpass", emptySet()))
        org.junit.Assert.assertEquals(com.shantanu.nmaps.data.Structure.Underpass, i.structure("NH 48", setOf("tunnel")))
        org.junit.Assert.assertEquals(com.shantanu.nmaps.data.Structure.Flyover, i.structure("IFFCO Chowk Flyover", emptySet()))
        org.junit.Assert.assertNull(i.structure("MG Road", setOf("motorway")))
        org.junit.Assert.assertEquals("Keep left at the fork to take the flyover", i.text("fork", "slight left", "", null, com.shantanu.nmaps.data.Structure.Flyover))
        org.junit.Assert.assertEquals("Continue onto NH 48, through the underpass", i.text("continue", "straight", "NH 48", null, com.shantanu.nmaps.data.Structure.Underpass))
        org.junit.Assert.assertEquals("Turn left onto Golf Course Road Underpass", i.text("turn", "left", "Golf Course Road Underpass", null, com.shantanu.nmaps.data.Structure.Underpass))
    }
}

class TerrainAndLimitsTest {
    @org.junit.Test fun terrariumDecoding() {
        // 0 m is encoded as R=128, G=0, B=0; Everest-ish 8848 m as R=162, G=144.
        org.junit.Assert.assertEquals(0.0, com.shantanu.nmaps.data.Terrain.decode(128, 0, 0), 0.01)
        org.junit.Assert.assertEquals(8848.0, com.shantanu.nmaps.data.Terrain.decode(162, 144, 0), 0.01)
    }

    @org.junit.Test fun resampleKeepsEnds() {
        val pts = listOf(com.shantanu.nmaps.data.LatLon(28.0, 77.0), com.shantanu.nmaps.data.LatLon(28.01, 77.0))
        val r = com.shantanu.nmaps.data.Terrain.resample(pts, 100.0, 500)
        org.junit.Assert.assertEquals(pts.first(), r.first())
        org.junit.Assert.assertEquals(pts.last(), r.last())
        org.junit.Assert.assertEquals(13, r.size) // 0, 100 … 1100 m, plus the end at 1112 m
    }

    @org.junit.Test fun nearestLimit() {
        val road = listOf(com.shantanu.nmaps.data.LatLon(28.0, 77.0), com.shantanu.nmaps.data.LatLon(28.01, 77.0))
        val side = listOf(com.shantanu.nmaps.data.LatLon(28.005, 77.0005), com.shantanu.nmaps.data.LatLon(28.005, 77.002))
        val m = com.shantanu.nmaps.data.SpeedLimitMap(listOf(road to 60, side to 30))
        org.junit.Assert.assertEquals(60, m.at(com.shantanu.nmaps.data.LatLon(28.002, 77.00003)))
        org.junit.Assert.assertNull(m.at(com.shantanu.nmaps.data.LatLon(28.002, 77.003)))
    }
}

class SmoothingTest {
    @org.junit.Test fun noiseDoesNotBecomeClimb() {
        // Flat ground at 220 m with ±4 m grid noise: smoothed, it shouldn't add up to real climbing.
        val noisy = (0 until 120).map { 220.0 + if (it % 2 == 0) 4.0 else -4.0 }
        val pts = noisy.mapIndexed { i, e -> com.shantanu.nmaps.gpx.GpxPoint(28.0 + i * 0.0004, 77.0, e) }
        val raw = com.shantanu.nmaps.gpx.TrackStats.of(pts, hysteresis = 3.0).gain
        val smoothed = com.shantanu.nmaps.data.Terrain.smooth(noisy)
        val clean = com.shantanu.nmaps.gpx.TrackStats.of(pts.mapIndexed { i, p -> p.copy(ele = smoothed[i]) }, hysteresis = 3.0).gain
        org.junit.Assert.assertTrue("raw noise counts as climb: $raw", raw > 400)
        org.junit.Assert.assertTrue("smoothed climb should be near zero: $clean", clean < 5)
    }
}
