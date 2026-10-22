package com.shantanu.nmaps.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.UiState
import com.shantanu.nmaps.data.Step
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatDuration
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import com.shantanu.nmaps.ui.theme.NothingRed

fun glyphFor(step: Step): DotGlyph = when {
    step.type == "arrive" -> Glyphs.Arrive
    step.type in setOf("roundabout", "rotary", "roundabout turn") -> Glyphs.Roundabout
    step.modifier == "uturn" -> Glyphs.UTurn
    step.modifier == "slight left" -> Glyphs.SlightLeft
    step.modifier == "slight right" -> Glyphs.SlightRight
    step.modifier?.contains("left") == true -> Glyphs.Left
    step.modifier?.contains("right") == true -> Glyphs.Right
    else -> Glyphs.Straight
}

private val MatrixBlack = Color(0xFF000000)
private val MatrixOff = Color(0xFF1E1E1E)

/**
 * The turn banner. Always black, like the Glyph Matrix on the back of the phone, in either theme:
 * the next turn is drawn on a full 9x9 dot grid, and its dots light up bottom-to-top when it changes.
 */
@Composable
fun NavBanner(s: UiState) {
    val nav = s.nav ?: return
    val route = s.route ?: return
    val context = LocalContext.current
    val step = route.steps.getOrNull(nav.progress.stepIndex) ?: return
    val then = route.steps.getOrNull(nav.progress.stepIndex + 1)

    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(nav.progress.stepIndex, nav.arrived) {
        if (animate) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(450))
        }
    }

    val shape = RoundedCornerShape(28.dp)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MatrixBlack, shape)
                .border(1.dp, MatrixOff, shape)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotIcon(
                when {
                    nav.arrived -> Glyphs.Arrive
                    nav.offTrack -> Glyphs.Warning
                    route.isTrack -> Glyphs.Straight
                    else -> glyphFor(step)
                },
                Modifier.size(84.dp),
                color = Color.White,
                accent = NothingRed,
                offColor = MatrixOff,
                reveal = reveal.value,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                if (nav.arrived) {
                    NText("ARRIVED", NType.dotLarge, color = Color.White)
                    NText(s.place?.name.orEmpty(), NType.body, color = Color.White.copy(alpha = 0.7f), maxLines = 2)
                } else if (route.isTrack) {
                    // Following a GPX track: no turns to call out, so show what's left and whether you're on it.
                    val d = formatDistance(if (nav.offTrack) nav.progress.offRoute else nav.progress.remaining)
                    Readout(d.value, d.unit, valueStyle = NType.display, color = if (nav.offTrack) NothingRed else Color.White)
                    NText(
                        if (nav.offTrack) "Off the track. Head back to the line." else "left on ${route.via}",
                        NType.body, color = Color.White.copy(alpha = 0.8f), maxLines = 2,
                    )
                } else {
                    val d = formatDistance(nav.progress.toManeuver)
                    Readout(d.value, d.unit, valueStyle = NType.display, color = Color.White)
                    NText(step.instruction, NType.body, color = Color.White.copy(alpha = 0.8f), maxLines = 2)
                    if (step.lanes.size >= 2) {
                        Spacer(Modifier.height(10.dp))
                        Lanes(step.lanes)
                    }
                    if (nav.rerouting) {
                        Spacer(Modifier.height(6.dp))
                        NText("Finding a new route", NType.caption, color = NothingRed)
                    }
                }
            }
        }
        if (!nav.arrived && then != null && !route.isTrack) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .background(MatrixBlack, CircleShape)
                    .border(1.dp, MatrixOff, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NText("Then", NType.caption, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(10.dp))
                DotIcon(glyphFor(then), Modifier.size(18.dp), color = Color.White, accent = NothingRed)
            }
        }
    }
}

/**
 * Lane guidance in the dot style: one small arrow per lane, lit white where the route goes and
 * dim where it doesn't, the way Google and Apple show it.
 */
@Composable
private fun Lanes(lanes: List<com.shantanu.nmaps.data.Lane>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        lanes.forEach { lane ->
            val glyph = when (lane.indications.firstOrNull { it != "none" }) {
                "left", "sharp left" -> Glyphs.Left
                "slight left" -> Glyphs.SlightLeft
                "right", "sharp right" -> Glyphs.Right
                "slight right" -> Glyphs.SlightRight
                "uturn" -> Glyphs.UTurn
                else -> Glyphs.Straight
            }
            DotIcon(glyph, Modifier.size(26.dp), color = if (lane.valid) Color.White else Color(0xFF3A3A3A))
        }
    }
}

@Composable
fun NavFooter(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    val nav = s.nav ?: return
    val route = s.route ?: return

    // Keep the screen awake for the whole trip.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.sheet().padding(20.dp)) {
        if (nav.arrived) {
            Pill("Done", vm::endNav, Modifier.fillMaxWidth(), style = PillStyle.Filled)
            return@Column
        }
        // Scale the route's own estimate by how much distance is left.
        val remainingSeconds = route.duration * (nav.progress.remaining / route.distance.coerceAtLeast(1.0))
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                formatDuration(remainingSeconds).forEach {
                    Readout(it.value, it.unit, Modifier.padding(end = 8.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val d = formatDistance(nav.progress.remaining)
                NText("${d.value} ${d.unit}", NType.bodyStrong)
                NText("Arrive ${arrivalTime(context, remainingSeconds)}", NType.caption, color = p.muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (s.settings.voice) "Mute" else "Unmute", vm::toggleVoice)
            Pill("End", vm::endNav, Modifier.weight(1f), style = PillStyle.Filled)
        }
    }
}
