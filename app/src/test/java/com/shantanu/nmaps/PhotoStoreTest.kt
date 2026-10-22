package com.shantanu.nmaps

import com.shantanu.nmaps.photos.GeoPhoto
import com.shantanu.nmaps.photos.PhotoStore
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStoreTest {
    @Test
    fun deletedPhotosDropOutAndOnlyNewOnesAreScanned() {
        val cached = mapOf(1L to GeoPhoto(1, 28.6, 77.2, 10), 2L to null, 3L to GeoPhoto(3, 12.9, 77.6, 30))
        val (kept, fresh) = PhotoStore.merge(cached, listOf(1L, 2L, 4L))
        assertEquals(setOf(1L, 2L), kept.keys)
        assertEquals(listOf(4L), fresh)
    }
}
