package com.shantanu.nmaps

import com.shantanu.nmaps.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    private val release = JSONObject(
        """{"tag_name":"v1.0.0-build.42","html_url":"https://github.com/x/y/releases/tag/v1.0.0-build.42","body":"Fixes",
            "assets":[{"name":"maps-debug-42.apk","size":10,"browser_download_url":"https://d/debug.apk"},
                      {"name":"maps-release-42.apk","size":20,"browser_download_url":"https://d/release.apk"}]}"""
    )

    @Test
    fun readsBuildNumberFromTag() {
        assertEquals(42, Updater.buildOf("v1.0.0-build.42"))
        assertNull(Updater.buildOf("v1.0.0"))
    }

    @Test
    fun prefersTheReleaseApk() {
        val r = Updater.parse(release)!!
        assertEquals(42, r.build)
        assertEquals("1.0.0", r.version)
        assertEquals("https://d/release.apk", r.apkUrl)
        assertEquals(20L, r.apkBytes)
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        assertNull(Updater.parse(JSONObject("""{"tag_name":"v1.0.0-build.3","assets":[]}""")))
    }
}
