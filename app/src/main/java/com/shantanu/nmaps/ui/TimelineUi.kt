package com.shantanu.nmaps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatDuration
import com.shantanu.nmaps.timeline.Trip
import com.shantanu.nmaps.timeline.Visit
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimelineSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = vm.timeline.ui
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember(ui.dayStart) { mutableStateOf<Int?>(null) }
    LaunchedEffect(ui.path) { vm.fitTimeline() }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyphs.Back, "Previous day", vm.timeline::previousDay, Modifier.size(40.dp), iconModifier = Modifier.size(14.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                NText(
                    if (vm.timeline.isToday) "TODAY" else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ui.dayStart)).uppercase(),
                    NType.dotMedium,
                )
                val trips = ui.segments.filterIsInstance<Trip>()
                val visits = ui.segments.count { it is Visit }
                if (ui.segments.isNotEmpty()) {
                    NText("$visits places, ${formatDistance(trips.sumOf { it.distance })} travelled", NType.caption, color = p.muted)
                }
            }
            if (!vm.timeline.isToday) RoundButton(Glyphs.Forward, "Next day", vm.timeline::nextDay, Modifier.size(40.dp), iconModifier = Modifier.size(14.dp))
            else Spacer(Modifier.size(40.dp))
        }
        Spacer(Modifier.height(10.dp))
        Segmented(listOf(false, true), ui.month != null, { if (it) "Month" else "Day" }) { month ->
            if (month) vm.timeline.showMonth() else vm.timeline.showDay()
        }
        Spacer(Modifier.height(10.dp))
        Suggestions(vm)
        if (ui.month == null) HealthRow(vm)

        when {
            ui.month != null && !ui.loading -> MonthView(vm)
            !vm.state.settings.timeline && ui.segments.isEmpty() -> {
                EmptyState(
                    Glyphs.Clock, "Timeline is off", "Keep a private history of the places you go, stored only on this phone.",
                    action = "Turn on in settings", onAction = vm::openSettings, compact = true,
                )
            }
            ui.loading -> SkeletonRows(3, Modifier.padding(vertical = 12.dp))
            ui.segments.isEmpty() -> EmptyState(Glyphs.Clock, "Quiet day", "No places or trips were saved on this day.", compact = true)
            else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                itemsIndexed(ui.segments) { i, seg ->
                    Column(Modifier.animateItem()) {
                    Row(
                        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                            .pressable(
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                if (seg is Visit) "Edit ${ui.names[i] ?: "place"}" else "Edit trip, ${(seg as Trip).mode.label}",
                            ) { editing = if (editing == i) null else i },
                    ) {
                        // The spine: a ring for each place, dots for each trip.
                        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            Canvas(Modifier.fillMaxHeight().width(24.dp)) {
                                val cx = size.width / 2
                                if (seg is Visit) {
                                    drawCircle(p.content, 7.dp.toPx(), Offset(cx, 14.dp.toPx()))
                                    drawCircle(p.surface, 4.dp.toPx(), Offset(cx, 14.dp.toPx()))
                                } else {
                                    var y = 4.dp.toPx()
                                    while (y < size.height) {
                                        drawCircle(p.red, 1.6.dp.toPx(), Offset(cx, y)); y += 6.dp.toPx()
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            when (seg) {
                                is Visit -> {
                                    NText(ui.names[i] ?: "Place", NType.bodyStrong, maxLines = 1)
                                    NText("${time.format(Date(seg.start))} – ${time.format(Date(seg.end))}", NType.caption, color = p.muted)
                                }
                                is Trip -> {
                                    NText(seg.mode.label, NType.body)
                                    NText(
                                        "${formatDistance(seg.distance)}, ${formatDuration((seg.end - seg.start) / 1000.0).joinToString(" ")}",
                                        NType.caption, color = p.muted,
                                    )
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(editing == i) {
                        SegmentEditor(vm, i, seg, ui.names[i]) { editing = null }
                    }
                    }
                }
            }
        }

        if (ui.segments.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("GPX", {
                    scope.launch { vm.exportTimelineDay()?.let { shareFile(context, it, title = "Export day") } }
                }, glyph = Glyphs.Share)
                Pill(if (confirmDelete) "Tap to confirm" else "Delete day", {
                    if (confirmDelete) {
                        vm.timeline.deleteDay(); confirmDelete = false
                    } else confirmDelete = true
                }, Modifier.weight(1f))
            }
            NText("GPX export works with photo apps to geotag pictures by time.", NType.caption, Modifier.padding(top = 8.dp), color = p.muted)
        }
    }
}

/** Steps and workouts for the day from Health Connect, above the places and trips. */
@Composable
private fun HealthRow(vm: MapViewModel) {
    val p = LocalPalette.current
    val h = vm.timeline.ui.health
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    if (!vm.state.settings.timeline && h == null) return
    when {
        !vm.state.settings.health -> return
        h == null -> NText("Activity from Health Connect isn't available right now.", NType.caption, Modifier.padding(bottom = 8.dp), color = p.muted)
        else -> Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Readout("%,d".format(h.steps), "steps", valueStyle = NType.dotMedium)
                Spacer(Modifier.weight(1f))
                NText("Health Connect", NType.caption, color = p.muted)
            }
            h.sessions.forEach { s ->
                Row(Modifier.padding(top = 6.dp)) {
                    NText("${time.format(Date(s.start))} – ${time.format(Date(s.end))}", NType.caption, Modifier.width(118.dp), color = p.muted)
                    NText("${s.label}, ${formatDuration((s.end - s.start) / 1000.0).joinToString(" ")}", NType.caption, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Inline editor under a timeline row: name a place, fix a trip's mode, or delete either. */
@Composable
private fun SegmentEditor(vm: MapViewModel, index: Int, seg: com.shantanu.nmaps.timeline.Segment, name: String?, done: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp, bottom = 12.dp)) {
        when (seg) {
            is Visit -> {
                var text by remember { mutableStateOf(name.orEmpty()) }
                Box(Modifier.fillMaxWidth().background(p.surfaceRaised, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (text.isEmpty()) NText("Name this place", NType.body, color = p.muted)
                    androidx.compose.foundation.text.BasicTextField(
                        text, { text = it.take(40) }, singleLine = true,
                        textStyle = NType.body.copy(color = p.content), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.red),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Save name", { vm.timeline.renameVisit(index, text); done() }, style = PillStyle.Filled, height = 38.dp)
                    Pill("Delete visit", { vm.timeline.deleteSegment(index); done() }, height = 38.dp)
                }
            }
            is Trip -> {
                Segmented(
                    listOf(com.shantanu.nmaps.timeline.TripMode.Walking, com.shantanu.nmaps.timeline.TripMode.Cycling, com.shantanu.nmaps.timeline.TripMode.Driving),
                    seg.mode, { it.label },
                ) { vm.timeline.setTripMode(index, it) }
                Spacer(Modifier.height(8.dp))
                Pill("Delete trip", { vm.timeline.deleteSegment(index); done() }, height = 38.dp)
            }
        }
    }
}

/** "Looks like home": offered from history when Home or Work isn't set yet. */
@Composable
private fun Suggestions(vm: MapViewModel) {
    val p = LocalPalette.current
    val ui = vm.timeline.ui
    val items = listOfNotNull(
        ui.suggestedHome?.let { com.shantanu.nmaps.data.Shortcut.Home to it },
        ui.suggestedWork?.let { com.shantanu.nmaps.data.Shortcut.Work to it },
    ).filter { (k, _) -> if (k == com.shantanu.nmaps.data.Shortcut.Home) vm.state.home == null else vm.state.work == null }
    items.forEach { (kind, at) ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).background(p.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotIcon(if (kind == com.shantanu.nmaps.data.Shortcut.Home) Glyphs.Home else Glyphs.Work, Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            NText(
                if (kind == com.shantanu.nmaps.data.Shortcut.Home) "This looks like home, from where you spend nights." else "This looks like work, from weekday daytimes.",
                NType.caption, Modifier.weight(1f),
            )
            Pill("Set", {
                vm.setShortcutAt(kind, at)
                vm.timeline.dismissSuggestions()
            }, style = PillStyle.Filled, height = 34.dp)
        }
    }
}

@Composable
private fun MonthView(vm: MapViewModel) {
    val p = LocalPalette.current
    val m = vm.timeline.ui.month ?: return
    if (m.days == 0) {
        EmptyState(Glyphs.Clock, "Quiet month", "No places or trips were saved this month.", compact = true)
        return
    }
    Row {
        Column(Modifier.weight(1f)) {
            NText("Days", NType.caption, color = p.muted)
            Readout("${m.days}", "", valueStyle = NType.dotMedium)
        }
        Column(Modifier.weight(1f)) {
            NText("Places", NType.caption, color = p.muted)
            Readout("${m.visits}", "visits", valueStyle = NType.dotMedium)
        }
        Column(Modifier.weight(1f)) {
            NText("Travelled", NType.caption, color = p.muted)
            formatDistance(m.distanceByMode.values.sum()).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
        }
    }
    Spacer(Modifier.height(12.dp))
    m.distanceByMode.entries.sortedByDescending { it.value }.forEach { (mode, d) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            NText(mode.label, NType.body, Modifier.weight(1f))
            NText(formatDistance(d).toString(), NType.body, color = p.muted)
        }
    }
    if (m.topPlaces.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Most time spent")
        m.topPlaces.forEachIndexed { i, (_, ms) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                NText(vm.timeline.ui.monthNames[i] ?: "Place ${i + 1}", NType.body, Modifier.weight(1f), maxLines = 1)
                NText(formatDuration(ms / 1000.0).joinToString(" "), NType.body, color = p.muted)
            }
        }
    }
}
