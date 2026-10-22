package com.shantanu.nmaps.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.nav.formatClock
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatElevation
import com.shantanu.nmaps.nav.formatPace
import com.shantanu.nmaps.nav.formatSpeed
import com.shantanu.nmaps.record.ActivitySummary
import com.shantanu.nmaps.record.RecStatus
import com.shantanu.nmaps.record.RecordState
import com.shantanu.nmaps.record.Sport
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.text.DateFormat
import java.util.Date

/** GPS quality as 0–4 lit columns, from fix accuracy — Strava's signal indicator in dots. */
@Composable
fun GpsSignal(accuracy: Float?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val level = when {
        accuracy == null -> 0
        accuracy <= 5 -> 4
        accuracy <= 10 -> 3
        accuracy <= 20 -> 2
        else -> 1
    }
    Canvas(modifier.size(width = 22.dp, height = 18.dp)) {
        val pitch = size.width / 4
        val r = pitch * 0.32f
        for (col in 0 until 4) for (row in 0..col) {
            val color = if (col < level) (if (level <= 1) p.red else p.content) else p.faint
            drawCircle(color, r, Offset(pitch * (col + 0.5f), size.height - pitch * (row + 0.5f)))
        }
    }
}

@Composable
fun RecordHeader(vm: MapViewModel, rec: RecordState) {
    val p = LocalPalette.current
    val fix = vm.state.fix
    val accuracy = if (rec.status != RecStatus.Idle) rec.accuracy else fix?.accuracy
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Glyphs.Back, "Back", { vm.back() }, Modifier.size(56.dp))
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.height(56.dp).background(p.surface, CircleShape).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GpsSignal(accuracy)
            Spacer(Modifier.width(12.dp))
            NText(
                when {
                    accuracy == null -> "Waiting for GPS"
                    accuracy > 20 -> "Weak GPS signal"
                    else -> "GPS ready"
                },
                NType.bodyStrong,
            )
            if (rec.satellites > 0) {
                Spacer(Modifier.width(8.dp))
                NText("${rec.satellites} satellites", NType.caption, color = p.muted)
            }
        }
    }
}

@Composable
fun RecordSheet(vm: MapViewModel, rec: RecordState) {
    val s = vm.state
    val finished = s.finished
    Column(Modifier.sheet().padding(20.dp)) {
        when {
            finished != null -> Summary(vm, finished)
            rec.status == RecStatus.Idle -> Ready(vm)
            else -> Live(vm, rec)
        }
    }
}

@Composable
private fun Ready(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Steps and the notification are extras; record either way.
        vm.startRecording()
    }
    Segmented(Sport.entries, s.sport, { it.label }, vm::setSport)
    if (s.activities.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Recent")
        s.activities.take(3).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    NText(a.name, NType.bodyStrong, maxLines = 1)
                    NText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.startedAt)), NType.caption, color = p.muted)
                }
                val d = formatDistance(a.distance)
                NText("${d.value} ${d.unit}", NType.body)
                NText(formatClock(a.movingMs), NType.body, Modifier.padding(start = 16.dp), color = p.muted)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Pill(
        "Start ${s.sport.label.lowercase()}",
        {
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (wanted.isEmpty()) vm.startRecording() else permissions.launch(wanted.toTypedArray())
        },
        Modifier.fillMaxWidth(),
        style = PillStyle.Filled,
        glyph = Glyphs.Record,
    )
}

@Composable
private fun Live(vm: MapViewModel, rec: RecordState) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(if (rec.status == RecStatus.Recording) p.red else p.faint) }
        Spacer(Modifier.width(10.dp))
        NText(
            when (rec.status) {
                RecStatus.Recording -> "RECORDING"
                RecStatus.Paused -> "PAUSED"
                else -> "AUTO-PAUSED"
            },
            NType.dotLabel,
            color = if (rec.status == RecStatus.Recording) p.content else p.muted,
        )
        Spacer(Modifier.weight(1f))
        NText(rec.sport.label, NType.caption, color = p.muted)
    }
    Spacer(Modifier.height(8.dp))
    NText(formatClock(rec.movingMs), NType.display)
    Spacer(Modifier.height(16.dp))
    StatGrid(statsFor(rec.sport, rec.movingMs, rec.distance, rec.elevationGain, rec.speed, rec.cadence, rec.steps, rec.usingBarometer))
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rec.status == RecStatus.Paused) {
            Pill("Resume", vm::resumeRecording, Modifier.weight(1f))
            Pill("Finish", vm::finishRecording, Modifier.weight(1f), style = PillStyle.Filled)
        } else {
            Pill("Pause", vm::pauseRecording, Modifier.fillMaxWidth(), style = PillStyle.Filled)
        }
    }
}

@Composable
private fun Summary(vm: MapViewModel, a: ActivitySummary) {
    val p = LocalPalette.current
    val context = LocalContext.current
    NText(a.name, NType.title)
    NText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.startedAt)), NType.caption, color = p.muted)
    Spacer(Modifier.height(16.dp))
    val avgSpeed = if (a.movingMs > 0) (a.distance / (a.movingMs / 1000.0)).toFloat() else 0f
    StatGrid(
        listOf(Stat("Moving time", formatClock(a.movingMs), "")) +
            statsFor(a.sport, a.movingMs, a.distance, a.elevationGain, avgSpeed, 0, a.steps, null)
    )
    val stats by androidx.compose.runtime.produceState<com.shantanu.nmaps.gpx.TrackStats?>(null, a.id) { value = vm.activityStats(a) }
    stats?.takeIf { it.profile.size >= 2 }?.let { st ->
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            NText("Elevation", NType.caption, Modifier.weight(1f), color = p.muted)
            NText("${formatElevation(st.minEle ?: 0.0)} – ${formatElevation(st.maxEle ?: 0.0)}", NType.caption, color = p.muted)
        }
        Spacer(Modifier.height(6.dp))
        ElevationProfile(st.profile, rows = 7)
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("GPX", {
            val file = vm.gpxFile(a)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share ${a.name}"))
        }, glyph = Glyphs.Share)
        Pill("Delete", { vm.deleteActivity(a) })
        Pill("Done", vm::closeSummary, Modifier.weight(1f), style = PillStyle.Filled)
    }
}

private data class Stat(val label: String, val value: String, val unit: String)

/** Pace for runs and walks, speed for rides; cadence and steps where a step counter makes sense. */
private fun statsFor(
    sport: Sport, movingMs: Long, distance: Double, gain: Double, speed: Float, cadence: Int, steps: Int, barometer: Boolean?,
): List<Stat> {
    val d = formatDistance(distance)
    return buildList {
        add(Stat("Distance", d.value, d.unit))
        if (sport.showsPace) formatPace(movingMs, distance).let { add(Stat("Avg pace", it.value, it.unit)) }
        else formatSpeed(speed).let { add(Stat("Speed", it.value, it.unit)) }
        formatElevation(gain).let { add(Stat(if (barometer == true) "Elevation (barometer)" else "Elevation gain", it.value, it.unit)) }
        if (sport.showsPace && cadence > 0) add(Stat("Cadence", "$cadence", "spm"))
        if (sport.showsPace && steps > 0) add(Stat("Steps", "$steps", ""))
    }
}

@Composable
private fun StatGrid(stats: List<Stat>) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        stats.chunked(2).forEach { row ->
            Row {
                row.forEach { stat ->
                    Column(Modifier.weight(1f)) {
                        NText(stat.label, NType.caption, color = p.muted)
                        Readout(stat.value, stat.unit, valueStyle = NType.dotMedium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
