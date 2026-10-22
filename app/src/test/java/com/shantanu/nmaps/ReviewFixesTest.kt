package com.shantanu.nmaps

import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.offline.engine.LiveTileSource
import com.shantanu.nmaps.record.Recorder
import com.shantanu.nmaps.record.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFixesTest {
    @Test fun longDiagonalRouteNeedsACorridorNotABox() {
        // ~40 km diagonal drive (Gurgaon to north Delhi): the old bounding box needed 600+ tiles.
        val line = (0..40).map { LatLon(28.40 + it * 0.0075, 77.00 + it * 0.0075) }
        val tiles = LiveTileSource.cover(line, 60.0)
        assertTrue("corridor tiles: ${tiles.size}", tiles.size in 20..LiveTileSource.MAX_FETCH)
    }

    @Test fun recordingIgnoresFixesFromBeforeItStarted() {
        Recorder.start(Sport.Walk, now = 1_000_000L)
        // A cached last-known location from an hour ago, far away.
        Recorder.onFix(Fix(28.0, 77.0, 5f, 0f, null, null, 1_000_000L - 3_600_000L))
        Recorder.onFix(Fix(28.4420, 77.1030, 5f, 1.5f, null, null, 1_001_000L))
        Recorder.onFix(Fix(28.4425, 77.1030, 5f, 1.5f, null, null, 1_031_000L))
        val s = Recorder.state.value
        assertEquals("only the real walk counts", 55.0, s.distance, 10.0)
        Recorder.finish()
    }
}
