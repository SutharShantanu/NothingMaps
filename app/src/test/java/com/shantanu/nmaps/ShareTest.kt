package com.shantanu.nmaps

import com.shantanu.nmaps.share.LiveLocation
import com.shantanu.nmaps.share.ShareCrypto
import com.shantanu.nmaps.share.ShareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTest {
    @Test fun encryptsAndDecrypts() {
        val key = ShareCrypto.newKey()
        val c1 = ShareCrypto.encrypt(key, "hello")
        val c2 = ShareCrypto.encrypt(key, "hello")
        assertNotEquals("fresh nonce each time", c1, c2)
        assertEquals("hello", ShareCrypto.decrypt(key, c1))
    }

    @Test fun wrongKeyOrTamperingFails() {
        val key = ShareCrypto.newKey()
        val c = ShareCrypto.encrypt(key, "secret place")
        assertNull(ShareCrypto.decrypt(ShareCrypto.newKey(), c))
        val bytes = ShareCrypto.decode(c).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(ShareCrypto.decrypt(key, ShareCrypto.encode(bytes)))
        assertNull(ShareCrypto.decrypt(key, "garbage"))
    }

    @Test fun linkIsFoundInsideAMessage() {
        val link = ShareLink.new()
        val message = "I'm sharing my location until 6 pm.\nOpen in Maps: ${link.uri}\nhttps://osm.org/x"
        assertEquals(link, ShareLink.find(message))
        assertNull(ShareLink.find("nmaps://live/short/abc"))
    }

    @Test fun payloadRoundTrips() {
        val loc = LiveLocation("Sam", 28.1, 77.2, 8f, 3.5f, 64, 1000L, 5000L, destination = "Office", eta = 4000L)
        assertEquals(loc, LiveLocation.fromJson(loc.toJson()))
        val bare = loc.copy(battery = null, destination = null, eta = null, stopped = true)
        assertEquals(bare, LiveLocation.fromJson(bare.toJson()))
    }
}
