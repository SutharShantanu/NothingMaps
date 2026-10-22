package com.shantanu.nmaps

import com.shantanu.nmaps.data.Category
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Shortcut
import com.shantanu.nmaps.data.VoiceCommand
import com.shantanu.nmaps.timeline.TimelineInsights
import com.shantanu.nmaps.timeline.Trip
import com.shantanu.nmaps.timeline.TripMode
import com.shantanu.nmaps.timeline.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAndInsightsTest {
    @Test fun voiceCommands() {
        assertEquals(VoiceCommand.NavigateShortcut(Shortcut.Home), VoiceCommand.parse("Navigate home"))
        assertEquals(VoiceCommand.NavigateShortcut(Shortcut.Work), VoiceCommand.parse("take me to the office"))
        assertEquals(VoiceCommand.Navigate("Cyber Hub"), VoiceCommand.parse("Directions to Cyber Hub."))
        assertEquals(VoiceCommand.Navigate("India Gate"), VoiceCommand.parse("navigate to India Gate please"))
        assertEquals(VoiceCommand.Nearby(Category.Fuel), VoiceCommand.parse("find petrol pump nearby"))
        assertEquals(VoiceCommand.Nearby(Category.Atm), VoiceCommand.parse("nearest ATM"))
        assertEquals(VoiceCommand.Nearby(Category.Pharmacy), VoiceCommand.parse("where is the closest pharmacy"))
        assertEquals(VoiceCommand.WhereAmI, VoiceCommand.parse("Where am I?"))
        assertEquals(VoiceCommand.Search("Sector 29 Gurgaon"), VoiceCommand.parse("Sector 29 Gurgaon"))
    }

    private val h = 3_600_000L
    private val homeP = LatLon(28.442, 77.103)
    private val workP = LatLon(28.495, 77.089)

    @Test fun homeWorkGuess() {
        // Five days: nights at home 21:00–08:00, daytime at work 09:30–18:00. Day 0 starts at a weekday midnight (UTC hours used).
        val visits = (0 until 5).flatMap { d ->
            val day = d * 24 * h
            listOf(Visit(day - 3 * h, day + 8 * h, homeP), Visit(day + 9 * h + h / 2, day + 18 * h, workP))
        }
        val (home, work) = TimelineInsights.homeAndWork(visits, { ((it / h) % 24 + 24).toInt() % 24 }, { true })
        assertEquals(homeP, home)
        assertEquals(workP, work)
        val (h2, w2) = TimelineInsights.homeAndWork(visits.take(1), { ((it / h) % 24 + 24).toInt() % 24 }, { true })
        assertEquals(homeP, h2)
        assertNull("one short workday isn't enough to call it work", w2)
    }

    @Test fun monthSummary() {
        val day = listOf(
            Visit(0, 8 * h, homeP),
            Trip(8 * h, 9 * h, listOf(homeP, workP), 6000.0, TripMode.Driving),
            Visit(9 * h, 17 * h, workP),
            Trip(17 * h, 18 * h, listOf(workP, homeP), 1500.0, TripMode.Walking),
        )
        val m = TimelineInsights.month(listOf(day, emptyList(), day))
        assertEquals(2, m.days)
        assertEquals(12000.0, m.distanceByMode[TripMode.Driving]!!, 0.1)
        assertEquals(4, m.visits)
        assertEquals(2, m.topPlaces.size)
    }
}
