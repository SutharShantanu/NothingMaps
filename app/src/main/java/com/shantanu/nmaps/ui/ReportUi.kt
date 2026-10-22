package com.shantanu.nmaps.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.Problem
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import java.util.Locale

/** Report a map problem as an OpenStreetMap note, which volunteer mappers pick up and fix. */
@Composable
fun ReportSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val r = rememberLast(vm.state.report) ?: return
    var problem by remember { mutableStateOf(if (r.placeName == null) Problem.MissingPlace else Problem.WrongInfo) }
    var details by remember { mutableStateOf("") }

    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle(if (r.sent != null) "Report sent" else "Report a problem", { vm.back() })
        val sent = r.sent
        if (sent != null) {
            EmptyState(
                Glyphs.Pin, "Thanks", "Your note #${sent.id} is public on OpenStreetMap. A volunteer mapper will look at it.",
                action = "View note", onAction = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sent.url))) }, compact = true,
            )
            return@Column
        }
        NText(
            (r.placeName?.let { "$it, " }.orEmpty()) + "%.5f, %.5f".format(Locale.US, r.at.lat, r.at.lon),
            NType.caption, color = p.muted,
        )
        Spacer(Modifier.height(12.dp))
        Problem.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { item ->
                    Pill(item.label, { problem = item }, Modifier.weight(1f), style = if (item == problem) PillStyle.Filled else PillStyle.Outlined, height = 40.dp)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).background(p.surfaceRaised, RoundedCornerShape(18.dp)).padding(14.dp)
        ) {
            if (details.isEmpty()) NText(
                when (problem) {
                    Problem.MissingPlace -> "What's here? E.g. \"Pharmacy, open 9–9, entrance on the side road\""
                    Problem.WrongInfo -> "What's wrong, and what's right?"
                    Problem.Road -> "E.g. \"This road is one-way north\" or \"Closed for metro work\""
                    Problem.Other -> "Describe the problem"
                },
                NType.body, color = p.muted,
            )
            BasicTextField(
                details, { details = it.take(800) },
                textStyle = NType.body.copy(color = p.content), cursorBrush = SolidColor(p.red),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        r.error?.let { NText(it, NType.caption, Modifier.padding(top = 8.dp), color = p.red) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NText("Notes are public, and don't include your name.", NType.caption, Modifier.weight(1f), color = p.muted)
            Spacer(Modifier.width(8.dp))
            if (r.sending) DotSpinner(Modifier.size(28.dp))
            else Pill("Send", { vm.sendReport(problem, details) }, style = PillStyle.Filled, height = 44.dp)
        }
    }
}
