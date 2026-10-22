package com.shantanu.nmaps.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import com.shantanu.nmaps.nav.formatElevation
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatDuration
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.util.Locale

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        DottedRule(Modifier.width(36.dp), dots = 5)
    }
}

@Composable
fun PlaceSheet(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    val place = rememberLast(s.place) ?: return
    val savedAs = s.saved.firstOrNull { it.key == place.key }
    val saved = savedAs != null
    val isParking = s.parking?.key == place.key
    var naming by remember(place.key) { mutableStateOf(false) }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        SheetHandle()
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                NText(place.name, NType.title, maxLines = 2)
                if (place.address.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    NText(place.address, NType.caption, color = p.muted, maxLines = 2)
                }
            }
            Spacer(Modifier.width(12.dp))
            RoundButton(Glyphs.Close, "Close", { vm.back() }, Modifier.size(40.dp))
        }

        if (s.fix != null || s.weather != null) Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            s.fix?.let { fix ->
                val d = formatDistance(distanceMeters(fix.point, place.point))
                Readout(d.value, d.unit, valueStyle = NType.dotMedium)
                NText("away", NType.caption, color = p.muted, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            s.weather?.let { WeatherBadge(it) }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Directions", vm::directions, Modifier.weight(1f), style = PillStyle.Filled)
            if (isParking) Pill("Clear", vm::clearParking, glyph = Glyphs.Parking)
            else Pill(
                if (saved) "Saved" else "Save",
                { if (saved) vm.unsave(place) else naming = true },
                glyph = if (saved) Glyphs.StarOn else Glyphs.Star,
                onLongClick = if (saved) ({ naming = true }) else null,
            )
            Pill("Share", { share(context, place) }, glyph = Glyphs.Share)
        }
        AnimatedVisibility(naming, enter = expandVertically(), exit = shrinkVertically()) {
            NameEditor(
                savedAs?.name ?: place.name, "Name this place",
                onSave = { vm.savePlace(place, it); naming = false },
                onCancel = { naming = false },
                Modifier.padding(top = 12.dp),
            )
        }
        if (savedAs != null && savedAs.name != place.name) {
            NText("Saved as “${savedAs.name}”. Hold Saved to rename.", NType.caption, Modifier.padding(top = 8.dp), color = p.muted)
        } else if (saved) {
            NText("Hold Saved to rename.", NType.caption, Modifier.padding(top = 8.dp), color = p.muted)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val coords = "%.5f, %.5f".format(java.util.Locale.US, place.lat, place.lon)
            NText(
                coords, NType.caption,
                Modifier.pressable(androidx.compose.foundation.shape.RoundedCornerShape(8.dp), "Copy coordinates") { copyText(context, coords); vm.notify("Coordinates copied") }
                    .padding(vertical = 6.dp),
                color = p.muted,
            )
            Spacer(Modifier.weight(1f))
            NText(
                "Report a problem", NType.caption,
                Modifier.pressable(androidx.compose.foundation.shape.RoundedCornerShape(8.dp), "Report a problem with this place") { vm.openReport(place.point, place.name) }
                    .padding(vertical = 6.dp),
                color = p.red,
            )
        }
    }
}

fun copyText(context: android.content.Context, text: String) {
    context.getSystemService(android.content.ClipboardManager::class.java)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("Coordinates", text))
}

private fun share(context: android.content.Context, place: Place) {
    shareText(context, listOf(place.name, place.address, osmLink(place.lat, place.lon)).filter { it.isNotBlank() }.joinToString("\n"))
}

/**
 * The directions planner: start, stops and destination as tappable rows (tap to change, × to drop a
 * stop), with a ⋮ menu to add a stop, swap the ends or clear the stops.
 */
@Composable
fun RouteHeader(vm: MapViewModel) {
    val s = vm.state
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        RoundButton(Glyphs.Back, "Back", { vm.back() }, Modifier.size(56.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).sheet().padding(horizontal = 14.dp, vertical = 6.dp)) {
            PlanRow(PlanDot.Start, s.plan.start?.name ?: "Your location", muted = s.plan.start == null, onClick = vm::editStart)
            s.plan.stops.forEachIndexed { i, stop ->
                PlanRow(PlanDot.Stop, stop.name, onClick = { vm.editStop(i) }, onRemove = { vm.removeStop(i) })
            }
            PlanRow(PlanDot.Destination, s.place?.name ?: "Choose destination", muted = s.place == null, last = true, onClick = vm::editDestination)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            RoundButton(Glyphs.More, "Route options", { menu = true }, Modifier.size(56.dp), iconModifier = Modifier.size(16.dp))
            if (menu) {
                Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 170), onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                    Column(Modifier.width(260.dp).sheet(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(vertical = 6.dp)) {
                        MenuItem("Add stop", Glyphs.Plus, enabled = s.plan.stops.size < com.shantanu.nmaps.MAX_STOPS) { menu = false; vm.addStop() }
                        MenuItem("Swap start and destination", Glyphs.Route, enabled = s.place != null) { menu = false; vm.swapEnds() }
                        if (s.plan.stops.isNotEmpty()) MenuItem("Remove all stops", Glyphs.Close) { menu = false; vm.clearStops() }
                        MenuItem("Change start", Glyphs.Locate) { menu = false; vm.editStart() }
                    }
                }
            }
        }
    }
}

private enum class PlanDot { Start, Stop, Destination }

@Composable
private fun PlanRow(dot: PlanDot, text: String, muted: Boolean = false, last: Boolean = false, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressable(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), "Change $text", onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dot for this row, with dotted joins up and down to its neighbours.
        Canvas(Modifier.width(16.dp).fillMaxHeight()) {
            val cx = size.width / 2
            val cy = size.height / 2
            var y = if (dot == PlanDot.Start) cy + 8.dp.toPx() else 2.dp.toPx()
            val end = if (last) cy - 8.dp.toPx() else size.height
            while (y < end) {
                if (kotlin.math.abs(y - cy) > 7.dp.toPx()) drawCircle(p.faint, 1.3.dp.toPx(), Offset(cx, y))
                y += 5.dp.toPx()
            }
            when (dot) {
                PlanDot.Start -> {
                    drawCircle(p.content, 5.dp.toPx(), Offset(cx, cy))
                    drawCircle(p.surface, 2.5.dp.toPx(), Offset(cx, cy))
                }
                PlanDot.Stop -> drawCircle(p.content, 4.dp.toPx(), Offset(cx, cy))
                PlanDot.Destination -> drawCircle(p.red, 5.dp.toPx(), Offset(cx, cy))
            }
        }
        Spacer(Modifier.width(12.dp))
        NText(text, if (muted) NType.body else NType.bodyStrong, Modifier.weight(1f), color = if (muted) p.muted else p.content, maxLines = 1)
        if (onRemove != null) {
            Box(Modifier.size(36.dp).pressable(CircleShape, "Remove stop $text", onRemove), contentAlignment = Alignment.Center) {
                DotIcon(Glyphs.Close, Modifier.size(11.dp), color = p.muted)
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, glyph: DotGlyph, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressable(androidx.compose.foundation.shape.RoundedCornerShape(12.dp), text, onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DotIcon(glyph, Modifier.size(16.dp), color = if (enabled) p.content else p.faint)
        Spacer(Modifier.width(14.dp))
        NText(text, NType.body, color = if (enabled) p.content else p.faint)
    }
}

@Composable
fun RouteSheet(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    var showSteps by remember { mutableStateOf(false) }
    val transit = s.travel == Travel.Transit

    Column(Modifier.sheet().heightIn(max = 640.dp).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        SheetHandle()
        Segmented(Travel.entries, s.travel, { it.label }, vm::setTravel)
        if (s.travel == Travel.Drive) {
            Spacer(Modifier.height(10.dp))
            // The public router can't exclude roads, but each option says what it uses, so pick accordingly.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AvoidChip("No tolls", s.settings.avoidTolls) { vm.setAvoid(tolls = it) }
                AvoidChip("No highways", s.settings.avoidHighways) { vm.setAvoid(highways = it) }
                AvoidChip("No ferries", s.settings.avoidFerries) { vm.setAvoid(ferries = it) }
            }
        }
        Spacer(Modifier.height(16.dp))

        val route = s.route
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when {
                s.place == null -> EmptyState(Glyphs.Pin, "Where to?", "Choose a destination to see routes.", action = "Choose destination", onAction = vm::editDestination, compact = true)
                s.routeLoading -> Column(Modifier.fillMaxWidth()) {
                    SkeletonReadout()
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DotSpinner(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        NText(
                            when {
                                transit -> "Checking timetables"
                                s.plan.stops.isEmpty() -> "Finding the best route"
                                else -> "Routing through ${s.plan.stops.size} stop${if (s.plan.stops.size > 1) "s" else ""}"
                            },
                            NType.caption, color = p.muted,
                        )
                    }
                }
                s.routeError != null -> EmptyState(
                    if (transit) Glyphs.Clock else Glyphs.Warning, if (transit) "No timetables" else "No route", s.routeError,
                    action = if (s.online) "Try again" else null, onAction = vm::retryRoute, compact = true,
                )
                transit && s.transit.isNotEmpty() -> TransitOptions(vm)
                route != null -> RouteDetails(vm, route, showSteps)
            }
            s.routeNotice?.let {
                Spacer(Modifier.height(10.dp))
                NText(it, NType.caption, color = p.red)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (transit) {
                NText(
                    "Timetables: Transitous",
                    NType.caption,
                    Modifier.weight(1f).pressable(androidx.compose.foundation.shape.RoundedCornerShape(8.dp), "Transit data sources") {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://transitous.org/sources/")))
                    }.padding(vertical = 12.dp),
                    color = p.muted,
                )
            } else {
                Pill(if (showSteps) "Hide" else "Steps", { showSteps = !showSteps })
                Pill(
                    "Start",
                    { if (route != null && s.fix != null) vm.startNav() },
                    Modifier.weight(1f),
                    style = if (route != null && s.fix != null) PillStyle.Filled else PillStyle.Outlined,
                )
            }
        }
    }
}

@Composable
private fun AvoidChip(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(32.dp)
            .pressable(CircleShape, "$text, ${if (on) "on" else "off"}") { onChange(!on) }
            .background(if (on) p.content else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
            .border(1.dp, if (on) p.content else p.faint, CircleShape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { NText(text, NType.caption, color = if (on) p.background else p.content) }
}

/** What a route uses that the avoid options care about, e.g. "tolls, highway". */
private fun usesText(r: com.shantanu.nmaps.data.Route): String? =
    listOfNotNull("tolls".takeIf { "toll" in r.classes }, "highway".takeIf { "motorway" in r.classes }, "ferry".takeIf { "ferry" in r.classes })
        .takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "uses ")

@Composable
private fun RouteDetails(vm: MapViewModel, route: com.shantanu.nmaps.data.Route, showSteps: Boolean) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    if (s.routes.size > 1) {
        s.routes.forEachIndexed { i, r ->
            val on = r == route
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), "Route ${i + 1}") { vm.chooseRoute(i) }
                    .background(if (on) p.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(10.dp)) { drawCircle(if (on) p.red else p.faint) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    NText(formatDuration(r.duration).joinToString(" "), NType.bodyStrong)
                    NText(
                        listOfNotNull(
                            formatDistance(r.distance).toString(),
                            r.via.takeIf { it.isNotBlank() }?.let { "via $it" },
                            usesText(r),
                            if (i == 0) "fastest" else null,
                        ).joinToString(", "),
                        NType.caption, color = p.muted, maxLines = 2,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            formatDuration(route.duration).forEach {
                Readout(it.value, it.unit, Modifier.padding(end = 10.dp), valueStyle = NType.display)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val d = formatDistance(route.distance)
            Readout(d.value, d.unit, valueStyle = NType.dotMedium)
            NText("Arrive ${arrivalTime(context, route.duration)}", NType.caption, color = p.muted)
        }
    }
    if (s.routes.size <= 1) usesText(route)?.let { NText(it.replaceFirstChar { c -> c.uppercase() }, NType.caption, color = p.muted) }

    // Climb matters on foot and by bike.
    s.routeElevation?.let { e ->
        if (e.profile.size >= 2) {
            Spacer(Modifier.height(14.dp))
            Row {
                NText("Climb ${formatElevation(e.gain)}", NType.caption, Modifier.weight(1f), color = p.muted)
                NText("Descent ${formatElevation(e.loss)}", NType.caption, color = p.muted)
            }
            Spacer(Modifier.height(6.dp))
            ElevationProfile(e.profile, rows = 7)
        }
    }

    AnimatedVisibility(showSteps, enter = expandVertically(), exit = shrinkVertically()) {
        Column(Modifier.padding(top = 16.dp)) {
            route.steps.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DotIcon(glyphFor(step), Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        NText(step.instruction, NType.body, maxLines = 2)
                        com.shantanu.nmaps.data.Instructions.laneHint(step.lanes)?.let { NText(it, NType.caption, color = p.muted) }
                    }
                    if (step.distance > 0 && i < route.steps.lastIndex) {
                        val d = formatDistance(step.distance)
                        NText("${d.value} ${d.unit}", NType.caption, color = p.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Transit options: departure and arrival, then each leg as a chip, and the chosen one's legs in full. */
@Composable
private fun TransitOptions(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    s.transit.forEachIndexed { i, it ->
        val on = i == s.transitIndex
        Column(
            Modifier
                .fillMaxWidth()
                .pressable(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), "Option ${i + 1}") { vm.chooseTransit(i) }
                .background(if (on) p.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NText("${time.format(java.util.Date(it.start))} – ${time.format(java.util.Date(it.end))}", NType.bodyStrong, Modifier.weight(1f))
                NText(formatDuration(it.durationSec.toDouble()).joinToString(" "), NType.bodyStrong)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                it.legs.forEachIndexed { k, leg ->
                    if (k > 0) NText("›", NType.caption, color = p.muted)
                    val label = if (leg.mode == com.shantanu.nmaps.transit.TransitMode.Walk) "Walk ${leg.durationSec / 60} min" else leg.route ?: leg.mode.label
                    val c = leg.color?.let { androidx.compose.ui.graphics.Color(it) }
                    NText(
                        label, NType.caption,
                        Modifier
                            .background(c ?: p.surface, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        color = if (c != null && c.luminance() < 0.5f) androidx.compose.ui.graphics.Color.White else p.content,
                        maxLines = 1,
                    )
                }
            }
            NText("${formatDistance(it.walkMeters)} walking, ${it.transfers} change${if (it.transfers == 1) "" else "s"}", NType.caption, color = p.muted)
            if (on) {
                Spacer(Modifier.height(8.dp))
                it.legs.forEach { leg ->
                    val text = if (leg.mode == com.shantanu.nmaps.transit.TransitMode.Walk) "Walk to ${leg.to.name}"
                    else "${leg.mode.label} ${leg.route.orEmpty()}${leg.headsign?.let { h -> " towards $h" }.orEmpty()}: ${leg.from.name} → ${leg.to.name}, ${leg.stops + 1} stops"
                    Row(Modifier.padding(vertical = 4.dp)) {
                        NText(time.format(java.util.Date(leg.start)), NType.caption, Modifier.width(64.dp), color = p.muted)
                        NText(text, NType.caption, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(p.surfaceRaised, CircleShape)
            .padding(4.dp)
    ) {
        items.forEach { t ->
            val on = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .pressable(CircleShape, label(t)) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(t)
                    }
                    .background(if (on) p.content else androidx.compose.ui.graphics.Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NText(label(t), NType.button, color = if (on) p.background else p.muted)
            }
        }
    }
}

fun arrivalTime(context: android.content.Context, seconds: Double): String {
    val at = java.util.Date(System.currentTimeMillis() + (seconds * 1000).toLong())
    return android.text.format.DateFormat.getTimeFormat(context).format(at).lowercase(Locale.getDefault())
}
