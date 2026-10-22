package com.shantanu.nmaps.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType

@Composable
fun NText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.content,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Big dot-matrix number with a small grotesque unit beside it, e.g. "18 min". */
@Composable
fun Readout(value: String, unit: String, modifier: Modifier = Modifier, valueStyle: TextStyle = NType.dotLarge, color: Color = LocalPalette.current.content) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        // Digits roll when the number changes: up for larger, down for smaller, like a flap display.
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val up = (targetState.filter { it.isDigit() }.toIntOrNull() ?: 0) >= (initialState.filter { it.isDigit() }.toIntOrNull() ?: 0)
                (slideInVertically(tween(220)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(220))) togetherWith
                    (slideOutVertically(tween(160)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(160))) using SizeTransform(clip = false)
            },
            label = "readout",
        ) { v -> NText(v, valueStyle, color = color) }
        NText(unit, NType.unit, color = color.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
}

/** Clickable with a short press-scale and a haptic tick, the way Nothing OS controls feel. */
@Composable
fun Modifier.pressable(
    shape: Shape,
    label: String? = null,
    onClick: () -> Unit,
): Modifier = pressable(shape, label, null, onClick)

/** As above, with a long-press action too. */
@Composable
fun Modifier.pressable(
    shape: Shape,
    label: String?,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val haptics = LocalHapticFeedback.current
    return this
        .scale(scale)
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            role = Role.Button,
            onLongClick = onLongClick?.let { long ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

enum class PillStyle { Filled, Outlined, Tonal }

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Outlined,
    glyph: DotGlyph? = null,
    height: Dp = 48.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val (bg, fg) = when (style) {
        PillStyle.Filled -> p.red to Color.White
        PillStyle.Outlined -> Color.Transparent to p.content
        PillStyle.Tonal -> p.surface to p.content
    }
    Row(
        modifier
            .height(height)
            .pressable(CircleShape, null, onLongClick, onClick)
            .background(bg, CircleShape)
            .then(
                when (style) {
                    PillStyle.Outlined -> Modifier.border(1.dp, p.faint, CircleShape)
                    PillStyle.Tonal -> Modifier.border(1.dp, p.line, CircleShape)
                    PillStyle.Filled -> Modifier
                }
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            DotIcon(glyph, Modifier.size(14.dp), color = fg, accent = if (style == PillStyle.Filled) Color.White else p.red)
            Box(Modifier.size(8.dp))
        }
        NText(text, NType.button, color = fg, maxLines = 1)
    }
}

/** Round map control (locate, compass). */
@Composable
fun RoundButton(
    glyph: DotGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .size(52.dp)
            .pressable(CircleShape, label, onClick)
            .background(if (active) p.content else p.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DotIcon(glyph, iconModifier.size(20.dp), color = if (active) p.background else p.content)
    }
}

/** Card surface for floating sheets: large radius, flat, no shadow — Nothing widgets sit flat. */
@Composable
fun Modifier.sheet(shape: Shape = RoundedCornerShape(28.dp)): Modifier {
    val p = LocalPalette.current
    return this
        .fillMaxWidth()
        // Sheets grow and shrink smoothly when their content changes (route options, steps, errors).
        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        .clip(shape)
        .background(p.surface, shape)
        .border(1.dp, p.line, shape)
}

/** Dotted rule used as the sheet grab handle and as a divider. */
@Composable
fun DottedRule(modifier: Modifier = Modifier, color: Color = LocalPalette.current.faint, dots: Int = 0) {
    Canvas(modifier.height(4.dp)) {
        val r = size.height / 2f
        val pitch = r * 3.2f
        val count = if (dots > 0) dots else (size.width / pitch).toInt()
        val start = (size.width - (count - 1) * pitch) / 2f
        repeat(count) { drawCircle(color, r * 0.8f, Offset(start + it * pitch, r)) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    NText(text.uppercase(), NType.dotLabel, modifier, color = LocalPalette.current.muted)
}
