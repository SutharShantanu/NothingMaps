package com.shantanu.nmaps

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.parseCoordinates
import com.shantanu.nmaps.share.ShareLink
import com.shantanu.nmaps.ui.MapScreen
import com.shantanu.nmaps.ui.theme.NTheme

class MainActivity : ComponentActivity() {
    private val vm: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Our own splash draws the same pin in the same place, so drop the system one at once
        // instead of letting it fade, which would make the pin blink.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        setContent {
            // Night mode follows the system, and the light sensor while navigating.
            NTheme(dark = isSystemInDarkTheme() || vm.autoNight) { MapScreen(vm) }
        }
        if (savedInstanceState == null) handle(intent)
        // Like Google Maps: while navigating, the trip shows over the lock screen.
        lifecycleScope.launch {
            snapshotFlow { vm.state.screen == Screen.Nav }.collect { navigating ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(navigating)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Things other apps hand us: geo: locations, live-location links, GPX files and shared text. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val data = intent.data
        when {
            data?.scheme == "nmaps" -> vm.addFriend(data.toString())
            data?.scheme == "geo" -> openGeo(data)
            intent.action == Intent.ACTION_VIEW && data != null -> vm.importGpx(data)
            intent.action == Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                when {
                    stream != null -> vm.importGpx(stream)
                    ShareLink.find(text) != null -> vm.addFriend(text)
                    text.isNotBlank() -> vm.searchFor(text.lineSequence().first().take(120))
                }
            }
        }
    }

    /** geo:lat,lon, geo:lat,lon?q=lat,lon(Label) and geo:0,0?q=search terms. */
    private fun openGeo(uri: Uri) {
        val ssp = uri.schemeSpecificPart.orEmpty()
        val q = ssp.substringAfter("q=", "").let { Uri.decode(it) }.takeIf { it.isNotBlank() }
        val label = q?.substringAfter('(', "")?.substringBefore(')')?.takeIf { it.isNotBlank() }
        val fromQuery = q?.substringBefore('(')?.let { parseCoordinates(it) }
        val fromPath = parseCoordinates(ssp.substringBefore('?').substringBefore(';'))?.takeIf { it.lat != 0.0 || it.lon != 0.0 }
        when {
            fromQuery != null -> vm.openGeo(fromQuery, label)
            q != null -> vm.searchFor(q)
            fromPath != null -> vm.openGeo(LatLon(fromPath.lat, fromPath.lon), label)
        }
    }
}
