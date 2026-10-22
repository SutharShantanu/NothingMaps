package com.shantanu.nmaps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.BaseMap
import com.shantanu.nmaps.data.Shortcut
import androidx.compose.foundation.layout.size
import com.shantanu.nmaps.health.HealthConnect
import com.shantanu.nmaps.record.RecStatus
import com.shantanu.nmaps.record.Recorder
import com.shantanu.nmaps.share.LiveShare
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType

/** Full-screen page with the standard header and scrolling body. */
@Composable
fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        PageHeader(title, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MenuScreen(vm: MapViewModel) {
    val s = vm.state
    val sharing by LiveShare.session.collectAsState()
    val rec by Recorder.state.collectAsState()
    val areas by vm.offline.areas.collectAsState()
    val recording = rec.status != RecStatus.Idle
    Page("Maps", { vm.back() }) {
        ListRow("Saved places", vm::openSaved, "${s.saved.size} saved", Glyphs.Star)
        ListRow("Timeline", vm::openTimeline, if (s.settings.timeline) "Your places and trips, day by day" else "Off. Turn on to keep a private location history", Glyphs.Clock)
        ListRow("Offline maps", vm::openOffline, "${areas.size} downloaded areas", Glyphs.Download)
        ListRow("Location sharing", vm::openShare,
            when {
                sharing != null -> "Sharing live now"
                vm.share.friends.isNotEmpty() -> "${vm.share.friends.size} sharing with you"
                else -> "Share where you are, live or once"
            }, Glyphs.Person)
        ListRow("Record activity", vm::openRecord, if (recording) "Recording now" else "Run, ride or walk", Glyphs.Record)
        ListRow("Map layers", vm::openLayers, "Satellite, terrain, transit, cycling, 3D", Glyphs.Layers)
        ListRow("Report a map problem", { vm.openReport() }, "Missing place, wrong road, anything off", Glyphs.Warning)
        ListRow("Settings", vm::openSettings, null, Glyphs.Gear)
        Spacer(Modifier.height(16.dp))
        NText(
            "Map data © OpenStreetMap contributors · Tiles © OpenFreeMap, OpenMapTiles · Search by Photon · Routing by OSRM (FOSSGIS)",
            NType.caption, color = LocalPalette.current.muted,
        )
    }
}

@Composable
fun SettingsScreen(vm: MapViewModel) {
    val s = vm.state.settings
    val context = LocalContext.current
    val p = LocalPalette.current
    var confirmWipe by remember { mutableStateOf(false) }

    // Android only grants "all the time" location from its own settings page, which this request opens.
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun hasBackground() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    Page("Settings", { vm.back() }) {
        SectionLabel("Navigation", Modifier.padding(start = 8.dp, top = 4.dp))
        ToggleRow("Voice guidance", s.voice, { vm.updateSettings(s.copy(voice = it)) }, "Spoken turns and off-route warnings", Glyphs.Mic)
        ToggleRow("Night map in the dark", s.autoNight, { vm.updateSettings(s.copy(autoNight = it)) }, "Uses the light sensor while navigating", Glyphs.Moon)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NText("Distance units", NType.bodyStrong)
            Segmented(listOf(false, true), s.imperial, { if (it) "Miles" else "Kilometres" }) { vm.updateSettings(s.copy(imperial = it)) }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("Places", Modifier.padding(start = 8.dp))
        listOf(Shortcut.Home to vm.state.home, Shortcut.Work to vm.state.work).forEach { (kind, place) ->
            ListRow(
                kind.label, { vm.changeShortcut(kind) },
                place?.address?.ifBlank { null } ?: if (place != null) "Set" else "Not set. Tap to choose",
                if (kind == Shortcut.Home) Glyphs.Home else Glyphs.Work,
            ) {
                if (place != null) RoundButton(Glyphs.Close, "Remove ${kind.label}", { vm.clearShortcut(kind) }, Modifier.size(40.dp), iconModifier = Modifier.size(12.dp))
                else DotIcon(Glyphs.Edit, Modifier.size(16.dp), color = p.muted)
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("Map controls", Modifier.padding(start = 8.dp))
        ToggleRow("Zoom buttons", s.zoomButtons, { vm.updateSettings(s.copy(zoomButtons = it)) }, "+ and − on the map")
        ToggleRow("Scale bar", s.scaleBar, { vm.updateSettings(s.copy(scaleBar = it)) })
        ToggleRow("Coordinates", s.coordinates, { vm.updateSettings(s.copy(coordinates = it)) }, "Crosshair with latitude and longitude of the map centre")
        ToggleRow("Rotate with two fingers", s.rotate, { vm.updateSettings(s.copy(rotate = it)) }, "Turn off to keep the map north-up")

        Spacer(Modifier.height(12.dp))
        SectionLabel("Privacy", Modifier.padding(start = 8.dp))
        ToggleRow(
            "Timeline",
            s.timeline,
            { on ->
                vm.updateSettings(s.copy(timeline = on))
                if (on && !hasBackground() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            when {
                !s.timeline -> "Keep a location history on this phone only"
                hasBackground() -> "Saving history, also while the app is closed"
                else -> "Saving while the app is open. Allow location \"all the time\" to save in the background"
            },
            Glyphs.Clock,
        )
        val connectHealth = rememberHealthConnect(vm)
        ToggleRow(
            "Health Connect", s.health,
            { on -> if (on) connectHealth() else vm.setHealth(false) },
            if (s.health) "Connected. Steps and workouts show in Timeline; turn off to disconnect"
            else "Connect to show steps and workouts in Timeline, from Google Fit and other apps",
            Glyphs.Steps,
        )
        if (s.health && HealthConnect.available(context)) {
            ListRow("Manage in Health Connect", { HealthConnect.openSettings(context) }, "See or change what Maps can read")
        }
        ListRow("Clear search history", vm::clearSearchHistory, "${vm.state.recents.size} recent searches")
        ListRow(
            if (confirmWipe) "Tap again to delete all history" else "Delete all timeline history",
            {
                if (confirmWipe) {
                    vm.timeline.deleteAll()
                    confirmWipe = false
                } else confirmWipe = true
            },
            "Removes every stored location from this phone",
        )

        Spacer(Modifier.height(12.dp))
        SectionLabel("About", Modifier.padding(start = 8.dp))
        NText(
            "Everything you save, record or keep in Timeline stays on this phone. Search goes to Photon, routes to " +
                "routing.openstreetmap.de and nearby places to Overpass. Live sharing is end-to-end encrypted and relayed through ntfy.sh.",
            NType.caption, Modifier.padding(8.dp), color = p.muted,
        )
    }
}

/** Asks Health Connect for read access and switches the setting on when granted. */
@Composable
fun rememberHealthConnect(vm: MapViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(HealthConnect.requestContract()) { granted ->
        if (granted.containsAll(HealthConnect.permissions)) vm.setHealth(true) else vm.notify("Health Connect access wasn't allowed")
    }
    return {
        if (HealthConnect.available(context)) launcher.launch(HealthConnect.permissions)
        else vm.notify("Health Connect isn't available on this phone")
    }
}

@Composable
fun LayersSheet(vm: MapViewModel) {
    val layers = vm.state.settings.layers
    androidx.compose.foundation.layout.Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle("Map layers", { vm.back() })
        Spacer(Modifier.height(12.dp))
        Segmented(BaseMap.entries, layers.base, { it.label }) { vm.setLayers(layers.copy(base = it)) }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Terrain", layers.terrain, { vm.setLayers(layers.copy(terrain = it)) }, "Hill shading from elevation data", Glyphs.Terrain)
        ToggleRow("Transit", layers.transit, { vm.setLayers(layers.copy(transit = it)) }, "Rail lines and stations", Glyphs.Train)
        ToggleRow("Cycling", layers.cycling, { vm.setLayers(layers.copy(cycling = it)) }, "Cycleways and bike-friendly roads", Glyphs.Bike)
        ToggleRow("3D buildings", layers.buildings3d, { vm.setLayers(layers.copy(buildings3d = it)) }, "Tilt the map with two fingers to see them", Glyphs.Building)
        val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (vm.photos.hasPermission()) vm.enablePhotos() else vm.notify("Photos need access to the gallery")
        }
        ToggleRow(
            "Photos", layers.photos,
            { on ->
                when {
                    !on -> vm.setLayers(layers.copy(photos = false))
                    vm.photos.hasPermission() -> vm.enablePhotos()
                    else -> mediaPermission.launch(vm.photos.permissions.toTypedArray())
                }
            },
            "Pictures from your gallery where they were taken; tap one to open it",
            Glyphs.Camera,
        )
        NText(
            "Live traffic isn't available: there's no free, open source for it.",
            NType.caption, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = LocalPalette.current.muted,
        )
    }
}
