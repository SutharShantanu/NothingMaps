package com.shantanu.nmaps

import com.shantanu.nmaps.gpx.Gpx
import com.shantanu.nmaps.gpx.GpxPoint
import com.shantanu.nmaps.gpx.GpxTrack
import com.shantanu.nmaps.gpx.GpxWaypoint
import com.shantanu.nmaps.gpx.TrackStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Strava" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <metadata><name>Sunday loop</name></metadata>
          <wpt lat="28.5" lon="77.1"><ele>220</ele><name>Water point</name><desc>Tap near gate</desc></wpt>
          <trk><name>Morning Ride</name><type>cycling</type>
            <trkseg>
              <trkpt lat="28.4000" lon="77.0000"><ele>200</ele><time>2026-10-18T06:00:00Z</time>
                <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
              <trkpt lat="28.4010" lon="77.0000"><ele>205</ele><time>2026-10-18T06:00:30.500Z</time></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="28.4020" lon="77.0000"><ele>212</ele><time>2026-10-18T11:31:00+05:30</time></trkpt>
            </trkseg>
          </trk>
          <rte><name>Planned</name>
            <rtept lat="28.41" lon="77.01"/><rtept lat="28.42" lon="77.02"/>
          </rte>
        </gpx>
    """.trimIndent()

    @Test fun parsesTracksSegmentsRoutesAndWaypoints() {
        val f = Gpx.parse(sample.byteInputStream())
        assertEquals("Sunday loop", f.name)
        assertEquals(2, f.tracks.size)
        val ride = f.tracks[0]
        assertEquals("Morning Ride", ride.name)
        assertEquals("cycling", ride.type)
        assertEquals("segments are joined into one track", 3, ride.points.size)
        assertEquals(205.0, ride.points[1].ele!!, 0.001)
        assertEquals(1_792_303_230_500L, ride.points[1].time)
        assertEquals("offset times are converted to UTC", 1_792_303_260_000L, ride.points[2].time)
        assertEquals("Planned", f.tracks[1].name)
        assertNull(f.tracks[1].points[0].time)
        assertEquals(listOf("Water point"), f.waypoints.map { it.name })
        assertEquals("Tap near gate", f.waypoints[0].description)
    }

    @Test fun roundTripsThroughWriter() {
        val track = GpxTrack("A & B <test>", "running", listOf(GpxPoint(1.0, 2.0, 3.5, 1_000L), GpxPoint(1.001, 2.001, null, 2_000L)))
        val text = Gpx.write("Export", listOf(track), listOf(GpxWaypoint("Café \"X\"", "", 5.0, 6.0)))
        val back = Gpx.parse(text.byteInputStream())
        assertEquals("A & B <test>", back.tracks[0].name)
        assertEquals(track.points, back.tracks[0].points)
        assertEquals("Café \"X\"", back.waypoints[0].name)
    }

    @Test fun rejectsNonGpx() {
        val e = runCatching { Gpx.parse("<kml></kml>".byteInputStream()) }.exceptionOrNull()
        assertTrue(e is Gpx.InvalidGpx)
        assertTrue(runCatching { Gpx.parse("not xml".byteInputStream()) }.exceptionOrNull() is Gpx.InvalidGpx)
    }

    @Test fun timeFormats() {
        assertEquals(0L, Gpx.parseTime("1970-01-01T00:00:00Z"))
        assertEquals(0L, Gpx.parseTime("1970-01-01T05:30:00+05:30"))
        assertEquals(0L, Gpx.parseTime("1970-01-01T00:00:00"))
        assertNull(Gpx.parseTime("yesterday"))
    }

    @Test fun statsIgnoreElevationNoise() {
        // 1 m wobble shouldn't count; a real 20 m climb then 10 m drop should.
        val eles = listOf(100.0, 101.0, 100.0, 101.0, 110.0, 120.0, 115.0, 110.0)
        val pts = eles.mapIndexed { i, e -> GpxPoint(28.0 + i * 0.001, 77.0, e, i * 60_000L) }
        val s = TrackStats.of(pts)
        assertEquals(20.0, s.gain, 0.001)
        assertEquals(10.0, s.loss, 0.001)
        assertEquals(7 * 60_000L, s.durationMs)
        assertEquals(778.0, s.distance, 5.0)
        assertNotNull(s.minEle)
        assertEquals(8, s.profile.size)
    }
}
