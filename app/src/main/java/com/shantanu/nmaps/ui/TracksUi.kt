package com.shantanu.nmaps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.nav.formatClock
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatDuration
import com.shantanu.nmaps.nav.formatElevation
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import kotlinx.coroutines.launch

@Composable
fun TracksScreen(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importGpx) }

    Page("Tracks", { vm.back() }) {
        NText(
            "Bring in routes from Strava, Komoot, AllTrails or a friend as GPX, then follow them with off-track alerts. " +
                "Export your saved places, planned routes, activities and timeline days the same way.",
            NType.caption, Modifier.padding(horizontal = 8.dp), color = p.muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Many apps label GPX as octet-stream or XML, so accept those and let the parser decide.
            Pill("Import GPX", { picker.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "application/xml", "*/*")) },
                Modifier.weight(1f), style = PillStyle.Filled, glyph = Glyphs.Plus)
            Pill("Export saved", {
                vm.exportSaved()?.let { shareFile(context, it, title = "Export saved places") }
            }, glyph = Glyphs.Share)
        }

        val pending = vm.tracks.pendingWaypoints
        if (pending.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(p.surface, RoundedCornerShape(20.dp)).padding(16.dp)) {
                NText("${pending.size} waypoints in that file", NType.bodyStrong)
                NText(pending.take(3).joinToString(", ") { it.name } + if (pending.size > 3) "…" else "", NType.caption, color = p.muted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Add to Saved", vm::savePendingWaypoints, style = PillStyle.Filled, height = 40.dp)
                    Pill("Dismiss", { vm.tracks.pendingWaypoints = emptyList() }, height = 40.dp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        val tracks = vm.tracks.tracks
        if (tracks.isEmpty()) {
            EmptyState(Glyphs.Route, "No tracks yet", "Import a GPX file, or open one from Strava, Komoot or AllTrails with Maps.")
        } else {
            SectionLabel("Library", Modifier.padding(start = 8.dp, bottom = 4.dp))
            tracks.forEach { t ->
                ListRow(
                    t.name,
                    { vm.openTrack(t) },
                    "${formatDistance(t.distance)}, ${formatElevation(t.gain)} climb",
                    Glyphs.Route,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.pressable(RoundedCornerShape(13.dp), if (t.visible) "Hide on map" else "Show on map") { vm.setTrackVisible(t, !t.visible) }
                    ) { Toggle(t.visible) }
                }
            }
        }
    }
}

@Composable
fun TrackSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track = rememberLast(vm.tracks.selected) ?: return
    val stats = vm.tracks.stats
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle(track.name, { vm.back() })
        Spacer(Modifier.height(12.dp))
        if (stats == null) {
            SkeletonReadout(); return@Column
        }
        Row {
            Column(Modifier.weight(1f)) {
                NText("Distance", NType.caption, color = p.muted)
                formatDistance(stats.distance).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
            }
            Column(Modifier.weight(1f)) {
                NText("Climb", NType.caption, color = p.muted)
                formatElevation(stats.gain).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
            }
            Column(Modifier.weight(1f)) {
                NText(if (stats.durationMs != null) "Recorded time" else "Hiking time", NType.caption, color = p.muted)
                val text = stats.durationMs?.let { formatClock(it) } ?: formatDuration(stats.hikingSeconds).joinToString(" ")
                NText(text, NType.dotMedium)
            }
        }
        if (stats.profile.size >= 2) {
            Spacer(Modifier.height(14.dp))
            ElevationProfile(stats.profile)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                NText("${formatElevation(stats.minEle ?: 0.0)} low", NType.caption, Modifier.weight(1f), color = p.muted)
                NText("${formatElevation(stats.maxEle ?: 0.0)} high", NType.caption, color = p.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Follow on foot", { vm.followTrack(Travel.Walk) }, Modifier.weight(1f), style = PillStyle.Filled)
            Pill("By bike", { vm.followTrack(Travel.Cycle) }, Modifier.weight(1f), style = PillStyle.Filled)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("To start", vm::directionsToTrackStart, glyph = Glyphs.Route)
            Pill("GPX", { scope.launch { shareFile(context, vm.trackFile(track), title = track.name) } }, glyph = Glyphs.Share)
            Pill(if (confirmDelete) "Confirm" else "Delete", {
                if (confirmDelete) vm.deleteTrack(track) else confirmDelete = true
            }, Modifier.weight(1f))
        }
    }
}
