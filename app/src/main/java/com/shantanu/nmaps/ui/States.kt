package com.shantanu.nmaps.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Loading indicator: eight dots in a ring, a red head chasing round with a fading tail,
 * like the progress pattern on the Glyph interface.
 */
@Composable
fun DotSpinner(modifier: Modifier = Modifier.size(28.dp), color: Color = LocalPalette.current.content) {
    val p = LocalPalette.current
    val t by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 8f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "head",
    )
    Canvas(modifier) {
        val r = size.minDimension / 2
        val dot = r * 0.2f
        val ring = r - dot
        val head = t.toInt()
        for (i in 0 until 8) {
            val age = ((head - i) % 8 + 8) % 8
            val a = (i / 8f) * 2 * PI.toFloat() - PI.toFloat() / 2
            val c = Offset(center.x + ring * cos(a), center.y + ring * sin(a))
            val paint = if (age == 0) p.red else lerp(color, p.line, (age / 7f).coerceIn(0f, 1f))
            drawCircle(paint, dot, c)
        }
    }
}

/** Phase 0..1 of a band of light sweeping left to right, shared by skeleton rows on screen. */
@Composable
private fun rememberWave(): Float {
    val w by rememberInfiniteTransition(label = "wave").animateFloat(
        -0.3f, 1.3f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    return w
}

/** A dotted placeholder bar; dots near the wave brighten. */
@Composable
private fun DotBar(fraction: Float, wave: Float, modifier: Modifier = Modifier, dotDp: Float = 2.2f) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth(fraction).height((dotDp * 2).dp)) {
        val r = dotDp.dp.toPx()
        val pitch = r * 2.8f
        val n = (size.width / pitch).toInt()
        for (i in 0 until n) {
            val x = r + i * pitch
            val glow = (1f - abs(x / size.width - wave) * 4f).coerceIn(0f, 1f)
            drawCircle(lerp(p.line, p.faint, glow), r, Offset(x, r))
        }
    }
}

/** Placeholder rows shaped like search results while they load. */
@Composable
fun SkeletonRows(count: Int = 4, modifier: Modifier = Modifier) {
    val wave = rememberWave()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(count) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotBar(1f, wave, Modifier.width(18.dp), dotDp = 3f)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DotBar(listOf(0.55f, 0.7f, 0.45f, 0.6f)[i % 4], wave)
                    DotBar(listOf(0.8f, 0.5f, 0.65f, 0.4f)[i % 4], wave)
                }
            }
        }
    }
}

/** Placeholder for the big ETA readout and its details while a route loads. */
@Composable
fun SkeletonReadout(modifier: Modifier = Modifier) {
    val wave = rememberWave()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DotBar(0.45f, wave, dotDp = 5f)
            DotBar(0.3f, wave)
        }
        Column(Modifier.weight(0.6f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DotBar(0.7f, wave, dotDp = 3f)
            DotBar(0.9f, wave)
        }
    }
}

/**
 * Empty or error state: a large dot-matrix icon that lights up bottom to top when it appears,
 * a short title, one line of direction, and optionally the action that fixes it.
 */
@Composable
fun EmptyState(
    glyph: DotGlyph,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val p = LocalPalette.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(glyph) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(650))
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotIcon(glyph, Modifier.size(if (compact) 48.dp else 88.dp), color = p.content, offColor = p.line, reveal = reveal.value)
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        NText(title.uppercase(), NType.dotLabel.copy(textAlign = TextAlign.Center), color = p.content)
        Spacer(Modifier.height(6.dp))
        NText(body, NType.body.copy(textAlign = TextAlign.Center), Modifier.padding(horizontal = 24.dp), color = p.muted)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Pill(action, onAction, style = PillStyle.Outlined, height = 44.dp)
        }
    }
}
