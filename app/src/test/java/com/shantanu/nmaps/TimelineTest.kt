package com.shantanu.nmaps

import com.shantanu.nmaps.timeline.HistoryPoint
import com.shantanu.nmaps.timeline.TimelineBuilder
import com.shantanu.nmaps.timeline.Trip
import com.shantanu.nmaps.timeline.TripMode
import com.shantanu.nmaps.timeline.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val min = 60_000L
    private val degPerMeter = 1 / 111_320.0

    /** Samples every [stepMs] while moving north at [speed] m/s from [lat]. */
    private fun move(t0: Long, lat: Double, meters: Double, speed: Double, stepMs: Long = 30_000): List<HistoryPoint> {
        val n = (meters / speed * 1000 / stepMs).toInt()
        return (1..n).map { i -> HistoryPoint(t0 + i * stepMs, lat + speed * i * stepMs / 1000 * degPerMeter, 77.0, 15f) }
    }

    private fun stay(t0: Long, lat: Double, minutes: Int): List<HistoryPoint> =
        (0..minutes step 3).map { HistoryPoint(t0 + it * min, lat + (it % 2) * 15 * degPerMeter, 77.0, 20f) }

    @Test fun daySplitsIntoVisitsAndTripsWithModes() {
        val home = stay(0, 28.0, 60)
        val walk = move(home.last().time, 28.0, 1500.0, 1.3)
        val cafeLat = walk.last().lat
        val cafe = stay(walk.last().time + min, cafeLat, 40)
        val drive = move(cafe.last().time, cafeLat, 12_000.0, 12.0)
        val office = stay(drive.last().time + min, drive.last().lat, 120)

        val segs = TimelineBuilder.segments(home + walk + cafe + drive + office)
        assertEquals(listOf("Visit", "Trip", "Visit", "Trip", "Visit"), segs.map { it::class.simpleName })
        assertEquals(TripMode.Walking, (segs[1] as Trip).mode)
        assertEquals(TripMode.Driving, (segs[3] as Trip).mode)
        assertEquals(12_000.0, (segs[3] as Trip).distance, 400.0)
        assertTrue((segs[4] as Visit).end - (segs[4] as Visit).start >= 115 * min)
    }

    @Test fun shortHopBetweenTwoStaysAtSamePlaceIsOneVisit() {
        val a = stay(0, 28.0, 30)
        // Wander 200 m and back in a few minutes: still the same visit.
        val out = move(a.last().time, 28.0, 200.0, 1.2, 20_000)
        val back = out.reversed().mapIndexed { i, p -> p.copy(time = out.last().time + (i + 1) * 20_000) }
        val b = stay(back.last().time + min, 28.0, 30)
        val segs = TimelineBuilder.segments(a + out + back + b)
        assertEquals(1, segs.size)
        assertTrue(segs[0] is Visit)
    }

    @Test fun inaccurateSamplesAreIgnored() {
        val pts = stay(0, 28.0, 30) + HistoryPoint(15 * min + 1, 28.5, 77.5, 2000f)
        val segs = TimelineBuilder.segments(pts)
        assertEquals(1, segs.size)
    }

    @Test fun modeFromSpeed() {
        fun mode(speed: Double) = TimelineBuilder.mode(move(0, 28.0, 3000.0, speed), 3000.0)
        assertEquals(TripMode.Walking, mode(1.4))
        assertEquals(TripMode.Cycling, mode(5.0))
        assertEquals(TripMode.Driving, mode(15.0))
    }
}
