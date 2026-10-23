package com.shantanu.nmaps

import com.shantanu.nmaps.data.AppLinks
import com.shantanu.nmaps.share.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    @Test
    fun placeLinkRoundTrips() {
        val url = AppLinks.place(28.44361, 77.10173, "G Town Wines & Co")
        assertTrue(url.startsWith("https://sutharshantanu.github.io/NothingMaps/?at=28.44361,77.10173&n="))
        val t = AppLinks.parse(url) as AppLinks.Target.Place
        assertEquals(28.44361, t.at.lat, 1e-6)
        assertEquals(77.10173, t.at.lon, 1e-6)
        assertEquals("G Town Wines & Co", t.name)
    }

    @Test
    fun liveLinkRoundTripsAndOldSchemeStillWorks() {
        val link = ShareLink.new()
        val web = AppLinks.live(link)
        assertEquals(link, (AppLinks.parse(web) as AppLinks.Target.Live).link)
        assertEquals(link, ShareLink.find("hey, open $web please"))
        assertEquals(link, ShareLink.find("nmaps://live/${link.topic}/${com.shantanu.nmaps.share.ShareCrypto.encode(link.key)}"))
    }

    @Test
    fun otherLinksAreIgnored() {
        assertNull(AppLinks.parse("https://www.openstreetmap.org/?mlat=1&mlon=2"))
        assertNull(AppLinks.parse(AppLinks.BASE))
    }
}
