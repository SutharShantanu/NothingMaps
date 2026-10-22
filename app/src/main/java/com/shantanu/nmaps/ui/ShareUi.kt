package com.shantanu.nmaps.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatElevation
import com.shantanu.nmaps.nav.formatSpeed
import com.shantanu.nmaps.share.Friend
import com.shantanu.nmaps.share.LiveShare
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val durations = listOf(15 * 60_000L to "15 min", 60 * 60_000L to "1 hour", 4 * 60 * 60_000L to "4 hours")

fun agoText(time: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - time) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
    }
}

@Composable
fun ShareSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val session by LiveShare.session.collectAsState()
    val error by LiveShare.error.collectAsState()
    var name by remember { mutableStateOf(vm.state.settings.shareName) }
    var duration by remember { mutableLongStateOf(durations[1].first) }
    val fix = vm.state.fix

    Column(Modifier.sheet().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle("Location sharing", { vm.back() })
        Spacer(Modifier.height(12.dp))
        val s = session
        if (s != null) {
            NText("Sharing live until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.until))}", NType.bodyStrong)
            NText(error ?: "People with your link see you move on their map.", NType.caption, color = if (error != null) p.red else p.muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Send link again", { shareText(context, inviteText(s.name, s.until, s.link.uri, fix?.let { LatLon(it.lat, it.lon) })) }, glyph = Glyphs.Share)
                Pill("Stop", vm::stopLiveShare, Modifier.weight(1f), style = PillStyle.Filled)
            }
        } else {
            NText("Your name", NType.caption, color = p.muted)
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(p.surfaceRaised, CircleShape).padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (name.isEmpty()) NText("Shown to the people you share with", NType.body, color = p.muted)
                BasicTextField(
                    name, { name = it.take(30) }, singleLine = true,
                    textStyle = NType.body.copy(color = p.content), cursorBrush = SolidColor(p.red),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Segmented(durations, durations.first { it.first == duration }, { it.second }) { duration = it.first }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Send current spot", {
                    fix?.let { shareText(context, "My location: ${osmLink(it.lat, it.lon)}\ngeo:${it.lat},${it.lon}") }
                }, glyph = Glyphs.Pin)
                Pill("Share live", {
                    val n = name.trim().ifEmpty { "A friend" }
                    val until = System.currentTimeMillis() + duration
                    val link = vm.startLiveShare(n, duration)
                    shareText(context, inviteText(n, until, link, fix?.let { LatLon(it.lat, it.lon) }), "Share live location")
                }, Modifier.weight(1f), style = PillStyle.Filled)
            }
            NText(
                "Live updates are end-to-end encrypted and relayed through ntfy.sh. Only people with your link can read them, and they need this app.",
                NType.caption, Modifier.padding(top = 8.dp), color = p.muted,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row {
            SectionLabel("Sharing with you", Modifier.weight(1f))
            NText("Paste link", NType.button, Modifier.pressable(CircleShape, "Paste a shared link") {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                vm.addFriend(text)
            }, color = p.red)
        }
        if (vm.share.friends.isEmpty()) {
            EmptyState(Glyphs.Person, "No one yet", "When someone sends you a Maps live-location link, open it or copy it and tap Paste link.", compact = true)
        }
        vm.share.friends.forEach { f -> FriendRow(vm, f) }
    }
}

private fun inviteText(name: String, until: Long, link: String, at: LatLon?): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
    return buildString {
        append("$name is sharing their live location with you until $time.\n")
        append("Open in Maps: $link\n")
        if (at != null) append("Right now: ${osmLink(at.lat, at.lon)}")
    }
}

@Composable
private fun FriendRow(vm: MapViewModel, f: Friend) {
    val last = f.last
    val subtitle = when {
        last == null -> "Waiting for the first update"
        last.stopped || last.until < System.currentTimeMillis() -> "Stopped sharing ${agoText(last.time)}"
        else -> {
            val away = vm.state.fix?.let { formatDistance(distanceMeters(it.point, LatLon(last.lat, last.lon))) }
            listOfNotNull("Updated ${agoText(last.time)}", away?.let { "$it away" }).joinToString(", ")
        }
    }
    ListRow(f.label, { vm.openFriend(f) }, subtitle, Glyphs.Person)
}

@Composable
fun FriendSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val f = rememberLast(vm.state.friend) ?: return
    val live = vm.share.friends.firstOrNull { it.link == f.link } ?: f
    val last = live.last
    // Re-render the "x min ago" text each minute.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle(live.label, { vm.back() })
        if (last == null) {
            NText("No update yet. Their phone sends one when it moves or every few minutes.", NType.body, Modifier.padding(vertical = 8.dp), color = p.muted)
        } else {
            val stopped = last.stopped || last.until < tick
            NText(if (stopped) "Stopped sharing ${agoText(last.time, tick)}" else "Updated ${agoText(last.time, tick)}", NType.caption, color = if (stopped) p.red else p.muted)
            Spacer(Modifier.height(12.dp))
            Row {
                vm.state.fix?.let {
                    Column(Modifier.weight(1f)) {
                        NText("Away", NType.caption, color = p.muted)
                        formatDistance(distanceMeters(it.point, LatLon(last.lat, last.lon))).let { d -> Readout(d.value, d.unit, valueStyle = NType.dotMedium) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    NText("Speed", NType.caption, color = p.muted)
                    formatSpeed(last.speed).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
                }
                last.battery?.let {
                    Column(Modifier.weight(1f)) {
                        NText("Battery", NType.caption, color = p.muted)
                        Readout("$it", "%", valueStyle = NType.dotMedium)
                    }
                }
            }
            if (last.destination != null && last.eta != null && !stopped) {
                Spacer(Modifier.height(10.dp))
                NText("Heading to ${last.destination}, arriving ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last.eta))}", NType.body)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Directions", { vm.directionsToFriend(live) }, Modifier.weight(1f), style = if (last != null) PillStyle.Filled else PillStyle.Outlined)
            Pill("Remove", { vm.removeFriend(live) })
        }
    }
}

@Composable
fun MeSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val fix = vm.state.fix ?: return
    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle("Your location", { vm.back() })
        NText("%.5f, %.5f".format(Locale.US, fix.lat, fix.lon), NType.body, Modifier.padding(top = 2.dp), color = p.muted)
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                NText("Accuracy", NType.caption, color = p.muted)
                formatDistance(fix.accuracy.toDouble()).let { Readout("±${it.value}", it.unit, valueStyle = NType.dotMedium) }
            }
            fix.altitude?.let { alt ->
                Column(Modifier.weight(1f)) {
                    NText("Altitude", NType.caption, color = p.muted)
                    formatElevation(alt).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
                }
            }
            Column(Modifier.weight(1f)) {
                NText("Speed", NType.caption, color = p.muted)
                formatSpeed(fix.speed).let { Readout(it.value, it.unit, valueStyle = NType.dotMedium) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Save parking", vm::saveParking, glyph = Glyphs.Parking)
            Pill("Share", { shareText(context, "My location: ${osmLink(fix.lat, fix.lon)}\ngeo:${fix.lat},${fix.lon}") }, glyph = Glyphs.Share)
            Pill("Live", vm::openShare, Modifier.weight(1f), style = PillStyle.Filled)
        }
    }
}
