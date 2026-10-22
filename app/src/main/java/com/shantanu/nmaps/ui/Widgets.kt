package com.shantanu.nmaps.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.io.File
import kotlin.math.roundToInt

/** Nothing-style switch: a pill that turns red with the thumb sliding right. */
@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (on) 1f else 0f, label = "toggle")
    Canvas(modifier.size(width = 46.dp, height = 26.dp)) {
        val r = size.height / 2
        drawRoundRect(if (on) p.red else p.faint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        drawCircle(androidx.compose.ui.graphics.Color.White, r - 4.dp.toPx(), Offset(r + x * (size.width - 2 * r), r))
    }
}

/** Weather and air quality for the spot on screen: sky glyph, temperature, and the AQI with a coloured dot. */
@Composable
fun WeatherBadge(c: com.shantanu.nmaps.data.Conditions, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val glyph = when (c.sky) {
        com.shantanu.nmaps.data.Sky.Sun -> Glyphs.Sun
        com.shantanu.nmaps.data.Sky.Moon -> Glyphs.Moon
        com.shantanu.nmaps.data.Sky.Cloud -> Glyphs.Cloud
        com.shantanu.nmaps.data.Sky.Rain -> Glyphs.Rain
        com.shantanu.nmaps.data.Sky.Snow -> Glyphs.Snow
        com.shantanu.nmaps.data.Sky.Storm -> Glyphs.Storm
    }
    val aqi = c.aqi
    Row(
        modifier
            .height(44.dp)
            .background(p.surface, CircleShape)
            .border(1.dp, p.line, CircleShape)
            .padding(start = 12.dp, end = 14.dp)
            .semantics { contentDescription = "${c.temperature}, ${c.sky.name.lowercase()}" + (aqi?.let { ", air quality $it, ${com.shantanu.nmaps.data.Weather.aqiLabel(it)}" } ?: "") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DotIcon(glyph, Modifier.size(26.dp), offColor = p.line)
        Spacer(Modifier.width(10.dp))
        NText(c.temperature, NType.dotMedium)
        if (aqi != null) {
            Spacer(Modifier.width(10.dp))
            val tone = when {
                aqi <= 50 -> androidx.compose.ui.graphics.Color(0xFF3DBE6C)
                aqi <= 100 -> androidx.compose.ui.graphics.Color(0xFFE2B93B)
                aqi <= 150 -> androidx.compose.ui.graphics.Color(0xFFF0883A)
                else -> p.red
            }
            Canvas(Modifier.size(8.dp)) { drawCircle(tone) }
            Spacer(Modifier.width(6.dp))
            NText("AQI $aqi", NType.caption, color = p.muted)
        }
    }
}

/** A tappable settings/list row with an optional dot glyph and trailing content. */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    glyph: DotGlyph? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(RoundedCornerShape(18.dp), title, onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Box(Modifier.size(40.dp).background(p.surface, CircleShape), contentAlignment = Alignment.Center) {
                DotIcon(glyph, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            NText(title, NType.bodyStrong, maxLines = 1)
            if (subtitle != null) NText(subtitle, NType.caption, color = p.muted, maxLines = 2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, glyph: DotGlyph? = null) {
    val haptics = LocalHapticFeedback.current
    Box(Modifier.semantics { role = Role.Switch; stateDescription = if (on) "On" else "Off" }) {
        ListRow(title, {
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!on)
        }, subtitle, glyph) { Toggle(on) }
    }
}

/** Progress as a row of dots filling left to right. */
@Composable
fun DottedProgress(progress: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2
        val pitch = r * 3f
        val count = (size.width / pitch).toInt().coerceAtLeast(1)
        val lit = (progress.coerceIn(0f, 1f) * count).roundToInt()
        repeat(count) { drawCircle(if (it < lit) p.red else p.faint, r * 0.85f, Offset(r + it * pitch, r)) }
    }
}

/**
 * Elevation profile as a dot-matrix bar chart: each column is a stretch of the track,
 * lit dots show its height between the lowest and highest point.
 */
@Composable
fun ElevationProfile(profile: List<Pair<Double, Double>>, modifier: Modifier = Modifier, rows: Int = 9) {
    val p = LocalPalette.current
    if (profile.size < 2) return
    Canvas(modifier.fillMaxWidth().height(80.dp)) {
        val pitch = size.height / rows
        val cols = (size.width / pitch).toInt().coerceAtLeast(2)
        val minE = profile.minOf { it.second }
        val maxE = profile.maxOf { it.second }
        val span = (maxE - minE).coerceAtLeast(10.0)
        val total = profile.last().first.coerceAtLeast(1.0)
        val r = pitch * 0.32f
        for (c in 0 until cols) {
            val along = total * c / (cols - 1)
            val e = profile.minByOrNull { kotlin.math.abs(it.first - along) }!!.second
            val level = 1 + ((e - minE) / span * (rows - 1)).roundToInt()
            for (row in 0 until rows) {
                drawCircle(
                    if (row < level) p.content else p.line,
                    r,
                    Offset(pitch * (c + 0.5f), size.height - pitch * (row + 0.5f)),
                )
            }
        }
    }
}

@Composable
fun SheetTitle(text: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NText(text, NType.title, Modifier.weight(1f), maxLines = 2)
        RoundButton(Glyphs.Close, "Close", onClose, Modifier.size(40.dp), iconModifier = Modifier.size(12.dp))
    }
}

/** Full-screen page header: back button and a dot-matrix title. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        RoundButton(Glyphs.Back, "Back", onBack, Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        NText(title.uppercase(), NType.dotLarge)
    }
}

fun shareText(context: Context, text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

fun shareFile(context: Context, file: File, mime: String = "application/gpx+xml", title: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(file.name, uri) }
    context.startActivity(Intent.createChooser(send, title))
}

fun osmLink(lat: Double, lon: Double) = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
