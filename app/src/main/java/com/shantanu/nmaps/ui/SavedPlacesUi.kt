package com.shantanu.nmaps.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.data.Shortcut
import com.shantanu.nmaps.nav.distanceMeters
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType

@Composable
fun SavedScreen(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var selectedCategory by remember { mutableStateOf("All") }

    val shortcutCount = listOfNotNull(s.home, s.work).size
    val totalCount = s.saved.size + shortcutCount

    // Gather dynamic categories preserving standard ordering
    val categories = remember(s.saved, shortcutCount) {
        val custom = s.saved.map { it.category }.distinct()
        val allCats = (listOf("All", "Shortcuts") + Place.STANDARD_CATEGORIES + custom).distinct()
        allCats.filter { cat ->
            when (cat) {
                "All" -> true
                "Shortcuts" -> true
                "Favorites", "Want to go" -> true
                else -> s.saved.any { it.category == cat }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Top navigation bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            RoundButton(Glyphs.Back, "Back", { vm.back() }, Modifier.size(52.dp))
            Spacer(Modifier.width(16.dp))
            NText("SAVED", NType.dotLarge, Modifier.weight(1f))
            if (s.saved.isNotEmpty()) {
                Pill(
                    text = "GPX",
                    onClick = { vm.exportSaved()?.let { shareFile(context, it, title = "Export saved places") } },
                    glyph = Glyphs.Share,
                    height = 40.dp,
                )
            }
        }

        // Category filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            items(categories, key = { it }) { cat ->
                val count = when (cat) {
                    "All" -> totalCount
                    "Shortcuts" -> shortcutCount
                    else -> s.saved.count { it.category == cat }
                }
                val label = if (count > 0) "$cat $count" else cat
                val isSelected = selectedCategory == cat
                val glyph = when (cat) {
                    "All" -> Glyphs.Star
                    "Shortcuts" -> Glyphs.Home
                    Place.CATEGORY_FAVORITES -> Glyphs.StarOn
                    Place.CATEGORY_WANT_TO_GO -> Glyphs.Bookmark
                    Place.CATEGORY_FOOD -> Glyphs.Food
                    else -> Glyphs.Tag
                }
                Pill(
                    text = label,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        selectedCategory = cat
                    },
                    style = if (isSelected) PillStyle.Filled else PillStyle.Tonal,
                    glyph = glyph,
                    height = 38.dp,
                )
            }
        }

        // Animated content based on category
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }) togetherWith
                    (fadeOut(tween(130)) + slideOutHorizontally(tween(180)) { -it / 8 })
            },
            label = "saved_category_content",
            modifier = Modifier.weight(1f),
        ) { currentCat ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (currentCat) {
                    "All" -> {
                        item {
                            SectionLabel("Shortcuts", Modifier.padding(start = 4.dp, bottom = 4.dp))
                            ShortcutsCard(s.home, s.work, s.fix, vm)
                        }

                        if (s.saved.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                SectionLabel("Saved places", Modifier.padding(start = 4.dp, bottom = 4.dp))
                            }
                            items(s.saved, key = { it.key }) { place ->
                                SavedPlaceItem(place, s.fix, vm)
                            }
                        } else if (s.home == null && s.work == null) {
                            item {
                                EmptyState(
                                    Glyphs.Star,
                                    "Nothing saved yet",
                                    "Set your Home and Work shortcuts above, or tap Save on any place card to find it here.",
                                )
                            }
                        }
                    }

                    "Shortcuts" -> {
                        item {
                            SectionLabel("Home & Work", Modifier.padding(start = 4.dp, bottom = 4.dp))
                            NText(
                                "Quick shortcuts for one-tap navigation from the search screen.",
                                NType.caption,
                                color = p.muted,
                                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                            )
                            ShortcutsCard(s.home, s.work, s.fix, vm)
                        }
                    }

                    else -> {
                        val matching = s.saved.filter { it.category == currentCat }
                        if (matching.isEmpty()) {
                            item {
                                val emptyGlyph = when (currentCat) {
                                    Place.CATEGORY_FAVORITES -> Glyphs.StarOn
                                    Place.CATEGORY_WANT_TO_GO -> Glyphs.Bookmark
                                    Place.CATEGORY_FOOD -> Glyphs.Food
                                    else -> Glyphs.Pin
                                }
                                val desc = when (currentCat) {
                                    Place.CATEGORY_FAVORITES -> "Tap Save on any place card to add it to your favorites."
                                    Place.CATEGORY_WANT_TO_GO -> "Save places you want to visit so you never lose them."
                                    Place.CATEGORY_FOOD -> "Save your favorite cafes, restaurants, and dining spots."
                                    else -> "No places saved under $currentCat yet."
                                }
                                EmptyState(emptyGlyph, "No $currentCat places", desc)
                            }
                        } else {
                            item {
                                SectionLabel("$currentCat (${matching.size})", Modifier.padding(start = 4.dp, bottom = 4.dp))
                            }
                            items(matching, key = { it.key }) { place ->
                                SavedPlaceItem(place, s.fix, vm)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Card holding Home and Work shortcuts with Nothing-style design. */
@Composable
private fun ShortcutsCard(home: Place?, work: Place?, fix: Fix?, vm: MapViewModel) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
    ) {
        ShortcutRow(
            title = Shortcut.Home.label,
            place = home,
            glyph = Glyphs.Home,
            fix = fix,
            onSelect = { if (home != null) vm.select(home) else vm.changeShortcut(Shortcut.Home) },
            onEdit = { vm.changeShortcut(Shortcut.Home) },
            onClear = { vm.clearShortcut(Shortcut.Home) },
        )
        DottedRule(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = p.line)
        ShortcutRow(
            title = Shortcut.Work.label,
            place = work,
            glyph = Glyphs.Work,
            fix = fix,
            onSelect = { if (work != null) vm.select(work) else vm.changeShortcut(Shortcut.Work) },
            onEdit = { vm.changeShortcut(Shortcut.Work) },
            onClear = { vm.clearShortcut(Shortcut.Work) },
        )
    }
}

/** Row for a shortcut (Home / Work). */
@Composable
private fun ShortcutRow(
    title: String,
    place: Place?,
    glyph: DotGlyph,
    fix: Fix?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(RoundedCornerShape(14.dp), label = title, onClick = onSelect)
            .padding(vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(p.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DotIcon(glyph, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NText(title, NType.bodyStrong)
            val subtitle = place?.address?.ifBlank { null }
                ?: if (place != null) "Set"
                else "Not set · Tap to choose"
            NText(
                subtitle,
                NType.caption,
                color = if (place != null) p.muted else p.red,
                maxLines = 1,
            )
        }
        if (place != null && fix != null) {
            val d = formatDistance(distanceMeters(fix.point, place.point))
            NText("${d.value} ${d.unit}", NType.caption, color = p.muted, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (place != null) {
            RoundButton(Glyphs.Edit, "Change $title", onEdit, Modifier.size(36.dp), iconModifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            RoundButton(Glyphs.Close, "Remove $title", onClear, Modifier.size(36.dp), iconModifier = Modifier.size(12.dp))
        } else {
            Pill("Set", onClick = onEdit, style = PillStyle.Outlined, height = 32.dp)
        }
    }
}

/** Saved place row with inline editing, category badge, and smooth animations. */
@Composable
private fun SavedPlaceItem(place: Place, fix: Fix?, vm: MapViewModel) {
    val p = LocalPalette.current
    var editing by remember(place.key) { mutableStateOf(false) }

    val categoryGlyph = when (place.category) {
        Place.CATEGORY_FAVORITES -> Glyphs.StarOn
        Place.CATEGORY_WANT_TO_GO -> Glyphs.Bookmark
        Place.CATEGORY_FOOD -> Glyphs.Food
        else -> Glyphs.Pin
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(RoundedCornerShape(12.dp), label = place.name) { vm.select(place) },
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(p.surfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DotIcon(categoryGlyph, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NText(place.name, NType.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (place.category != Place.CATEGORY_FAVORITES) {
                        Box(
                            Modifier
                                .background(p.surfaceRaised, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            NText(place.category, NType.caption, color = p.muted)
                        }
                    }
                }
                val addressLine = buildString {
                    if (fix != null) {
                        val d = formatDistance(distanceMeters(fix.point, place.point))
                        append("${d.value} ${d.unit}")
                        if (place.address.isNotBlank()) append(" · ")
                    }
                    if (place.address.isNotBlank()) append(place.address)
                }
                if (addressLine.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    NText(addressLine, NType.caption, color = p.muted, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(
                glyph = Glyphs.Edit,
                label = "Edit ${place.name}",
                onClick = { editing = !editing },
                modifier = Modifier.size(36.dp),
                iconModifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            RoundButton(
                glyph = Glyphs.Close,
                label = "Remove ${place.name}",
                onClick = { vm.unsave(place) },
                modifier = Modifier.size(36.dp),
                iconModifier = Modifier.size(12.dp),
            )
        }

        AnimatedVisibility(
            visible = editing,
            enter = expandVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)),
            exit = shrinkVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                DottedRule(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = p.line)
                NameEditor(
                    initial = place.name,
                    placeholder = "Name this place",
                    initialCategory = place.category,
                    categories = Place.STANDARD_CATEGORIES,
                    onSave = { name ->
                        vm.savePlace(place, name)
                        editing = false
                    },
                    onSaveWithCategory = { name, cat ->
                        vm.savePlace(place, name, cat)
                        editing = false
                    },
                    onCancel = { editing = false },
                )
            }
        }
    }
}
