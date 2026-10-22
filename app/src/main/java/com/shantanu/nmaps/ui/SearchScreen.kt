package com.shantanu.nmaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import com.shantanu.nmaps.data.Category
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.Pick
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType

@Composable
fun SearchScreen(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyphs.Back, "Back", { vm.back() }, Modifier.size(56.dp))
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(p.surface, CircleShape)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (s.query.isEmpty()) NText(
                        when (val pick = s.pick) {
                            is Pick.ForShortcut -> "Search for your ${pick.kind.label.lowercase()} address"
                            Pick.Start -> "Choose start"
                            Pick.Destination -> "Choose destination"
                            is Pick.Stop -> if (pick.index == null) "Add a stop" else "Change stop"
                            null -> "Search for a place or address"
                        },
                        NType.body, color = p.muted, maxLines = 1,
                    )
                    BasicTextField(
                        value = s.query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        textStyle = NType.body.copy(color = p.content),
                        cursorBrush = SolidColor(p.red),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (s.query.isNotEmpty()) {
                    RoundButton(Glyphs.Close, "Clear search", { vm.setQuery("") }, Modifier.size(40.dp), iconModifier = Modifier.size(12.dp))
                } else {
                    RoundButton(Glyphs.Mic, "Voice search", vm::startVoice, Modifier.size(40.dp), iconModifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            if (s.pick == Pick.Start && s.query.isBlank() && s.fix != null) {
                item {
                    ListRow("Your location", vm::useMyLocationAsStart, "Start from where you are now", Glyphs.Locate)
                }
            }
            if (s.pick != null && s.query.isBlank()) {
                item { ListRow("Choose on map", vm::pickOnMap, "Drop a pin anywhere", Glyphs.Pin) }
                val saved = listOfNotNull(s.home, s.work) + s.saved
                if (saved.isNotEmpty()) {
                    item { SectionLabel("Saved", Modifier.padding(top = 8.dp, bottom = 8.dp)) }
                    items(saved, key = { "saved-" + it.key }) { PlaceRow(it, Glyphs.StarOn, s.fix, Modifier.animateItem()) { vm.select(it) } }
                }
            }
            if (s.query.isBlank()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        items(Category.entries) { c ->
                            Pill(c.label, { vm.searchNearby(c) }, style = if (s.category == c) PillStyle.Filled else PillStyle.Tonal, height = 40.dp)
                        }
                    }
                }
            }
            when {
                s.query.isBlank() && s.category != null -> {
                    item { SectionLabel("${s.category.label} nearby", Modifier.padding(bottom = 8.dp)) }
                    when {
                        s.searching && s.results.isEmpty() -> item { SkeletonRows(5, Modifier.padding(vertical = 12.dp)) }
                        s.searchError != null && s.results.isEmpty() -> message(s.searchError)
                        else -> items(s.results, key = { "n" + it.key }) { PlaceRow(it, Glyphs.Pin, s.fix, Modifier.animateItem()) { vm.select(it) } }
                    }
                }
                s.query.isBlank() -> recents(vm)
                s.searchError != null && s.results.isEmpty() -> item {
                    EmptyState(if (s.online) Glyphs.Warning else Glyphs.Download, if (s.online) "Search unavailable" else "Offline", s.searchError)
                }
                s.searching && s.results.isEmpty() -> item { SkeletonRows(5, Modifier.padding(vertical = 12.dp)) }
                s.results.isEmpty() && !s.searching -> item {
                    EmptyState(Glyphs.Search, "No matches", "Nothing found for “${s.query.trim()}”. Check the spelling or add a town name.")
                }
                else -> {
                    s.searchError?.let { message(it) }
                    items(s.results, key = { it.key }) { PlaceRow(it, Glyphs.Pin, s.fix, Modifier.animateItem()) { vm.select(it) } }
                }
            }
        }
    }
}

private fun LazyListScope.recents(vm: MapViewModel) {
    val recents = vm.state.recents
    if (recents.isEmpty()) {
        item { EmptyState(Glyphs.Search, "Find anywhere", "Search places, addresses or coordinates. Long-press the map to drop a pin.") }
        return
    }
    item {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Recent", Modifier.weight(1f))
            NText(
                "Clear",
                NType.button,
                Modifier.pressable(RoundedCornerShape(8.dp), onClick = vm::clearRecents).padding(8.dp),
                color = LocalPalette.current.red,
            )
        }
    }
    items(recents, key = { "recent-" + it.key }) { PlaceRow(it, Glyphs.Clock, vm.state.fix, Modifier.animateItem()) { vm.select(it) } }
}

private fun LazyListScope.message(text: String) {
    item { NText(text, NType.body, Modifier.padding(vertical = 12.dp), color = LocalPalette.current.muted) }
}

@Composable
private fun PlaceRow(place: Place, glyph: DotGlyph, fix: Fix?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().pressable(RoundedCornerShape(16.dp), onClick = onClick)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            DotIcon(glyph, Modifier.size(18.dp), color = p.muted)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                NText(place.name, NType.bodyStrong, maxLines = 1)
                if (place.address.isNotBlank()) NText(place.address, NType.caption, color = p.muted, maxLines = 1)
            }
            if (fix != null) {
                val d = formatDistance(distanceMeters(fix.point, place.point))
                NText("${d.value} ${d.unit}", NType.caption, color = p.muted, modifier = Modifier.padding(start = 12.dp))
            }
        }
        DottedRule(Modifier.fillMaxWidth().padding(start = 34.dp), color = p.line)
    }
}

@Composable
fun SavedScreen(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyphs.Back, "Back", { vm.back() }, Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            NText("SAVED", NType.dotLarge, Modifier.weight(1f))
            if (s.saved.isNotEmpty()) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Pill("GPX", { vm.exportSaved()?.let { shareFile(context, it, title = "Export saved places") } }, glyph = Glyphs.Share, height = 40.dp)
            }
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            if (s.saved.isEmpty()) {
                item { EmptyState(Glyphs.Star, "Nothing saved", "Open any place and tap Save to keep it here, one tap away.") }
            } else {
                items(s.saved, key = { it.key }) { place ->
                    var renaming by remember(place.key) { mutableStateOf(false) }
                    Column(Modifier.animateItem()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { PlaceRow(place, Glyphs.StarOn, s.fix) { vm.select(place) } }
                            RoundButton(Glyphs.Edit, "Rename ${place.name}", { renaming = !renaming }, Modifier.padding(start = 8.dp).size(40.dp), iconModifier = Modifier.size(14.dp))
                            RoundButton(Glyphs.Close, "Remove ${place.name}", { vm.unsave(place) }, Modifier.padding(start = 8.dp).size(40.dp), iconModifier = Modifier.size(12.dp))
                        }
                        if (renaming) {
                            NameEditor(place.name, "Name this place", onSave = { vm.savePlace(place, it); renaming = false }, onCancel = { renaming = false },
                                Modifier.padding(start = 34.dp, top = 4.dp, bottom = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
