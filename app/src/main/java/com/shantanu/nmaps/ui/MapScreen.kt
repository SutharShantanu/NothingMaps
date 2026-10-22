package com.shantanu.nmaps.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shantanu.nmaps.MapViewModel
import com.shantanu.nmaps.Screen
import com.shantanu.nmaps.UiState
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Shortcut
import com.shantanu.nmaps.map.DotMap
import com.shantanu.nmaps.map.Follow
import com.shantanu.nmaps.map.MapMarker
import com.shantanu.nmaps.map.MapOverlays
import com.shantanu.nmaps.nav.formatClock
import com.shantanu.nmaps.nav.formatDistance
import com.shantanu.nmaps.nav.formatSpeed
import com.shantanu.nmaps.record.RecStatus
import com.shantanu.nmaps.record.RecordState
import com.shantanu.nmaps.record.Recorder
import com.shantanu.nmaps.timeline.Visit
import com.shantanu.nmaps.ui.theme.LocalPalette
import com.shantanu.nmaps.ui.theme.NType
import kotlin.math.abs

/** Screens drawn over the whole map rather than as a sheet on it. */
private val fullScreens = setOf(Screen.Search, Screen.Saved, Screen.Menu, Screen.Offline, Screen.Settings)

@Composable
fun MapScreen(vm: MapViewModel) {
    val s = vm.state
    val p = LocalPalette.current
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onLocationPermission(result.values.any { it })
    }
    LaunchedEffect(Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) vm.onLocationPermission(true)
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    BackHandler(enabled = s.screen != Screen.Map) { vm.back() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setForeground(true)
                Lifecycle.Event.ON_STOP -> vm.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setForeground(false)
        }
    }

    val rec by Recorder.state.collectAsStateWithLifecycle()
    val track = when {
        rec.status != RecStatus.Idle -> rec.track
        s.screen == Screen.Record && s.finished != null -> s.finishedTrack
        else -> null
    }

    // Splash until the map's style has loaded, held long enough for the pin to finish lighting up.
    var mapReady by rememberSaveable { mutableStateOf(false) }
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        splashDone = true
        // Never trap the user behind the splash if the map can't load (offline, no downloaded area).
        delay(3100)
        mapReady = true
    }

    Box(Modifier.fillMaxSize().background(p.background)) {
        DotMap(
            dark = p.isDark,
            layers = s.settings.layers,
            fix = s.fix,
            route = s.route,
            destination = s.place.takeIf { s.screen in setOf(Screen.Place, Screen.Route, Screen.Nav) },
            follow = s.follow,
            heading = { vm.heading },
            track = track,
            overlays = overlays(vm, s),
            commands = vm.camera,
            onBearing = vm::onBearing,
            onUserPan = vm::onUserPan,
            onTap = vm::onMapTap,
            onLongPress = vm::onLongPress,
            onMarkerTap = { id -> if (id.startsWith("photo:")) openPhoto(context, id.removePrefix("photo:").toLong()) else vm.onMarkerTap(id) },
            onMeTap = vm::onMeTap,
            onAlternativeTap = vm::chooseRoute,
            onCameraIdle = vm::onCameraIdle,
            onReady = { mapReady = true },
            onCameraMove = vm::onCameraMove,
            rotateGestures = s.settings.rotate,
            modifier = Modifier.fillMaxSize(),
        )

        if (s.screen == Screen.OfflineSelect) OfflineFrame()
        // The pin only appears while choosing a spot on the map, its tip on the camera centre.
        if (s.screen == Screen.PickOnMap) PickPin()

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Top: search bar, route header or turn banner.
            AnimatedContent(
                targetState = topSlot(s.screen),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "top",
            ) { slot ->
                when (slot) {
                    TopSlot.Search -> Column {
                        SearchBar(vm)
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                AnimatedVisibility(s.screen == Screen.Map, enter = fadeIn(), exit = fadeOut()) { QuickChips(vm) }
                                AnimatedVisibility(s.screen == Screen.Map && vm.mapWeather != null, enter = fadeIn(), exit = fadeOut()) {
                                    rememberLast(vm.mapWeather)?.let { WeatherBadge(it, Modifier.padding(top = 10.dp)) }
                                }
                            }
                            // Layers sits under the menu button, as in Google Maps.
                            RoundButton(Glyphs.Layers, "Map layers", vm::openLayers, Modifier.padding(top = 10.dp))
                        }
                    }
                    TopSlot.Route -> RouteHeader(vm)
                    TopSlot.Nav -> NavBanner(s)
                    TopSlot.Record -> RecordHeader(vm, rec)
                    TopSlot.None -> Spacer(Modifier.fillMaxWidth())
                }
            }

            AnimatedVisibility(!s.online && s.screen in setOf(Screen.Map, Screen.Place), enter = fadeIn(), exit = fadeOut()) { OfflineBanner() }
            CalibrationHint(vm)
            AnimatedVisibility(s.message != null, enter = fadeIn(), exit = fadeOut()) {
                val shown = rememberLast(s.message) ?: ""
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    NText(
                        shown, NType.bodyStrong,
                        Modifier.background(p.content, CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
                        color = p.background,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (s.screen !in setOf(Screen.Timeline, Screen.OfflineSelect, Screen.Share, Screen.Friend)) {
                MapControls(vm = vm, rec = rec, showSpeed = s.screen == Screen.Nav, attribution = s.screen == Screen.Map)
                Spacer(Modifier.height(12.dp))
            }

            // Bottom: shortcuts or the sheet for the current screen.
            AnimatedContent(
                targetState = s.screen,
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(160)) { it / 3 } + fadeOut(tween(140))) using SizeTransform(clip = false)
                },
                label = "bottom",
            ) { screen ->
                when (screen) {
                    Screen.Map -> Shortcuts(vm, rec)
                    Screen.Place -> PlaceSheet(vm)
                    Screen.Route -> RouteSheet(vm)
                    Screen.Nav -> NavFooter(vm)
                    Screen.Record -> RecordSheet(vm, rec)
                    Screen.PickOnMap -> PickOnMapSheet(vm)
                    Screen.Layers -> LayersSheet(vm)
                    Screen.OfflineSelect -> OfflineSelectSheet(vm)
                    Screen.Timeline -> TimelineSheet(vm)
                    Screen.Share -> ShareSheet(vm)
                    Screen.Friend -> FriendSheet(vm)
                    Screen.Me -> MeSheet(vm)
                    Screen.Report -> ReportSheet(vm)
                    else -> Spacer(Modifier.fillMaxWidth())
                }
            }
        }

        AnimatedContent(
            targetState = s.screen.takeIf { it in fullScreens },
            transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(260)) { -it / 12 }) togetherWith fadeOut(tween(140)) },
            label = "page",
        ) { screen ->
            when (screen) {
                Screen.Search -> SearchScreen(vm)
                Screen.Saved -> SavedScreen(vm)
                Screen.Menu -> MenuScreen(vm)
                Screen.Offline -> OfflineScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                else -> Box(Modifier)
            }
        }

        AnimatedVisibility(vm.listening, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            VoiceOverlay(onResult = vm::onVoice, onClose = vm::stopVoice)
        }

        AnimatedVisibility(!(mapReady && splashDone), enter = fadeIn(tween(0)), exit = fadeOut(tween(450))) { Splash() }
    }
}

/**
 * Cold-start screen, always black like a boot screen so it continues Android's own (black) launch
 * splash: the app's pin, centred where the system drew it, lights up dot by dot while the map loads.
 */
@Composable
private fun Splash() {
    // The system splash already showed the lit pin, so keep it lit and bring the wordmark in under it.
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) { word.animateTo(1f, tween(500)) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        DotIcon(Glyphs.Logo, Modifier.size(120.dp), color = androidx.compose.ui.graphics.Color.White)
        NText(
            "MAPS", NType.dotLarge,
            Modifier.padding(top = 220.dp).graphicsLayer { alpha = word.value; translationY = (1 - word.value) * 24f },
            color = androidx.compose.ui.graphics.Color.White,
        )
        Box(Modifier.fillMaxSize().padding(bottom = 72.dp), contentAlignment = Alignment.BottomCenter) {
            DotSpinner(Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color(0xFF8C8C8C))
        }
    }
}

/** Confirms the spot under the crosshair; the name fills in from a reverse lookup as the map settles. */
@Composable
private fun PickOnMapSheet(vm: MapViewModel) {
    val p = LocalPalette.current
    val pick = vm.state.mapPick
    Column(Modifier.sheet().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        SheetTitle("Move the map to the spot", { vm.back() })
        Spacer(Modifier.height(10.dp))
        NText(pick?.name ?: "Waiting for the map", NType.bodyStrong, maxLines = 1)
        NText(pick?.address.orEmpty().ifBlank { " " }, NType.caption, color = p.muted, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        Pill("Use this location", vm::confirmMapPick, Modifier.fillMaxWidth(), style = if (pick != null) PillStyle.Filled else PillStyle.Outlined, glyph = Glyphs.Pin)
    }
}

/** Markers and lines for whatever the current screen is about. */
private fun overlays(vm: MapViewModel, s: UiState): MapOverlays {
    val markers = buildList {
        vm.share.friends.forEach { f ->
            val last = f.last ?: return@forEach
            add(MapMarker("friend:${f.link.topic}", "friend", LatLon(last.lat, last.lon), f.label, f.label.take(1).uppercase()))
        }
        s.parking?.let { add(MapMarker("parking", "parking", it.point, "Parking", "P")) }
        if (s.screen in setOf(Screen.Route, Screen.Nav)) {
            s.plan.stops.forEachIndexed { i, stop -> add(MapMarker("stop:$i", "stop", stop.point, "${i + 1}. ${stop.name}")) }
        }
        if (s.category != null && s.screen in setOf(Screen.Place, Screen.Map)) {
            s.results.take(40).forEachIndexed { i, r -> add(MapMarker("nearby:$i", "nearby", r.point, r.name)) }
        }
        if (s.screen == Screen.Timeline) {
            val ui = vm.timeline.ui
            ui.segments.forEachIndexed { i, seg ->
                if (seg is Visit) add(MapMarker("visit:$i", "visit", seg.center, ui.names[i]))
            }
        }
    }
    val alternatives = if (s.screen == Screen.Route) {
        s.routes.mapIndexedNotNull { i, r -> if (r == s.route) null else i to r.points }
    } else emptyList()
    val history = when {
        s.screen == Screen.Timeline -> listOf(vm.timeline.ui.path)
        // Walking parts of a transit trip are dotted, rides solid, like the other apps.
        s.travel == com.shantanu.nmaps.data.Travel.Transit && s.screen == Screen.Route ->
            s.transit.getOrNull(s.transitIndex)?.legs?.filter { it.mode == com.shantanu.nmaps.transit.TransitMode.Walk }?.map { it.points }.orEmpty()
        else -> emptyList()
    }
    return MapOverlays(alternatives, history, markers, if (s.screen in setOf(Screen.Map, Screen.Place, Screen.Timeline)) vm.photoMarkers else emptyList())
}

/** Opens a gallery photo in whatever app shows pictures, the gallery included. */
private fun openPhoto(context: android.content.Context, id: Long) {
    val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private enum class TopSlot { Search, Route, Nav, Record, None }

private fun topSlot(screen: Screen) = when (screen) {
    Screen.Map, Screen.Place, Screen.Me -> TopSlot.Search
    Screen.Route -> TopSlot.Route
    Screen.Nav -> TopSlot.Nav
    Screen.Record -> TopSlot.Record
    else -> TopSlot.None
}

@Composable
private fun SearchBar(vm: MapViewModel) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(56.dp)
                .pressable(CircleShape, "Search", vm::openSearch)
                .background(p.surface, CircleShape)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotIcon(Glyphs.Search, Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
            NText("Search here", NType.body, Modifier.weight(1f), color = p.muted)
            Box(Modifier.size(40.dp).pressable(CircleShape, "Voice search", vm::startVoice), contentAlignment = Alignment.Center) {
                DotIcon(Glyphs.Mic, Modifier.size(18.dp), color = p.content)
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundButton(Glyphs.Menu, "Menu", vm::openMenu, Modifier.size(56.dp), iconModifier = Modifier.size(16.dp))
    }
}

/** A dotted scale bar sized to a round distance, like the one Google shows while zooming. */
@Composable
private fun ScaleBar(zoom: Float, lat: Double, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    // MapLibre's zoom is in 512-pixel tiles measured in dp.
    val metersPerDp = 78_271.517 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())
    val maxMeters = metersPerDp * 96
    val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(maxMeters)))
    val nice = listOf(5.0, 2.0, 1.0).map { it * magnitude }.first { it <= maxMeters }
    val widthDp = (nice / metersPerDp).toFloat()
    Column(modifier) {
        NText(com.shantanu.nmaps.nav.formatDistance(nice).toString(), NType.caption, color = p.content)
        Canvas(Modifier.width(widthDp.dp).height(8.dp)) {
            val r = 1.6.dp.toPx()
            var x = r
            while (x <= size.width) {
                drawCircle(p.content, r, androidx.compose.ui.geometry.Offset(x, size.height - r)); x += r * 3
            }
            drawCircle(p.content, r, androidx.compose.ui.geometry.Offset(r, size.height - r * 4))
            drawCircle(p.content, r, androidx.compose.ui.geometry.Offset(size.width - r, size.height - r * 4))
        }
    }
}

/** The app's dot pin held over the map centre while picking a spot; its tip marks the point. */
@Composable
private fun PickPin() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The glyph is 5 wide by 6 tall with the tip on the bottom row; lift it so the tip sits on the centre.
        DotIcon(Glyphs.Pin, Modifier.size(width = 40.dp, height = 48.dp).offset(y = (-24).dp))
    }
}

/** The centre's latitude and longitude; tap to copy, long-press to drop a pin there. */
@Composable
private fun CenterCoordinates(vm: MapViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val c = vm.cameraCenter ?: return
    val text = "%.5f, %.5f".format(java.util.Locale.US, c.lat, c.lon)
    NText(
        text, NType.caption,
        modifier
            .pressable(CircleShape, "Map centre $text. Tap to copy, hold to drop a pin", { vm.onLongPress(c) }) {
                copyText(context, text); vm.notify("Coordinates copied")
            }
            .background(p.surface, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The red directions button: opens the planner with your location as the start. */
@Composable
private fun DirectionsButton(onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(60.dp).pressable(CircleShape, "Directions", onClick).background(p.red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DotIcon(Glyphs.Directions, Modifier.size(24.dp), color = androidx.compose.ui.graphics.Color.White, accent = androidx.compose.ui.graphics.Color.White)
    }
}

/** Home, Work and Saved, one tap each, under the search bar. */
@Composable
private fun QuickChips(vm: MapViewModel) {
    val s = vm.state
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Home", { vm.openShortcut(Shortcut.Home) }, style = PillStyle.Tonal, glyph = Glyphs.Home, height = 40.dp,
            onLongClick = { vm.changeShortcut(Shortcut.Home) })
        Pill("Work", { vm.openShortcut(Shortcut.Work) }, style = PillStyle.Tonal, glyph = Glyphs.Work, height = 40.dp,
            onLongClick = { vm.changeShortcut(Shortcut.Work) })
        Pill(if (s.saved.isEmpty()) "Saved" else "Saved ${s.saved.size}", vm::openSaved, style = PillStyle.Tonal, glyph = Glyphs.Star, height = 40.dp)
    }
}

@Composable
private fun MapControls(vm: MapViewModel, rec: RecordState, showSpeed: Boolean, attribution: Boolean) {
    val s = vm.state
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        // Bottom-left: zoom, the centre's coordinates, the scale bar and attribution.
        Box(Modifier.weight(1f)) {
            if (showSpeed) SpeedCluster(s.fix?.speed ?: 0f, s.speedLimit)
            else Column(horizontalAlignment = Alignment.Start) {
                if (s.settings.zoomButtons) {
                    Column(Modifier.padding(bottom = 10.dp).background(p.surface, CircleShape)) {
                        RoundButton(Glyphs.Plus, "Zoom in", { vm.zoom(1.0) }, iconModifier = Modifier.size(14.dp))
                        RoundButton(Glyphs.Minus, "Zoom out", { vm.zoom(-1.0) }, iconModifier = Modifier.size(14.dp))
                    }
                }
                if (s.settings.coordinates && s.screen == Screen.Map) CenterCoordinates(vm, Modifier.padding(bottom = 8.dp))
                if (s.settings.scaleBar && s.screen in setOf(Screen.Map, Screen.Place, Screen.Me)) {
                    ScaleBar(vm.cameraZoom, vm.cameraCenter?.lat ?: 0.0, Modifier.padding(start = 6.dp, bottom = 6.dp))
                }
                if (attribution) NText("© OpenStreetMap contributors", NType.caption, color = p.muted.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            }
        }
        // Bottom-right, top to bottom: north (when turned), activity, my location, directions.
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedVisibility(abs(vm.bearing) > 1f, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                RoundButton(Glyphs.North, "Point north", vm::resetNorth, iconModifier = Modifier.rotate(-vm.bearing))
            }
            if (s.screen == Screen.Map) {
                RoundButton(
                    Glyphs.Steps,
                    if (rec.status != RecStatus.Idle) "Recording ${rec.sport.label.lowercase()}: ${formatClock(rec.movingMs)}" else "Record an activity",
                    vm::openRecord,
                    active = rec.status != RecStatus.Idle,
                )
            }
            if (s.hasLocation) {
                RoundButton(
                    if (s.follow == Follow.Compass) Glyphs.Heading else Glyphs.Locate,
                    when (s.follow) {
                        Follow.Flat -> "Turn map with compass"
                        Follow.Compass -> "Point map north"
                        else -> "Show my location"
                    },
                    vm::locate,
                    active = s.follow != Follow.None,
                )
            }
            if (s.screen == Screen.Map) DirectionsButton(vm::openPlanner)
        }
    }
}

/**
 * Speedometer, plus the posted limit as a red-ringed sign when known.
 * The speed turns red when more than 5 over the limit.
 */
@Composable
private fun SpeedCluster(speedMs: Float, limitKmh: Int?) {
    val p = LocalPalette.current
    val speed = formatSpeed(speedMs)
    val limit = limitKmh?.let { formatSpeed(it / 3.6f) }
    val over = limitKmh != null && speedMs * 3.6f > limitKmh + 5
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(76.dp).background(if (over) p.red else p.surface, CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NText(speed.value, NType.dotMedium, color = if (over) androidx.compose.ui.graphics.Color.White else p.content)
                NText(speed.unit, NType.caption, color = if (over) androidx.compose.ui.graphics.Color.White else p.muted)
            }
        }
        if (limit != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    drawCircle(androidx.compose.ui.graphics.Color.White)
                    drawCircle(p.red, size.minDimension / 2 - 3.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx()))
                }
                NText(limit.value, NType.dotMedium, color = androidx.compose.ui.graphics.Color.Black)
            }
        }
    }
}

@Composable
private fun CalibrationHint(vm: MapViewModel) {
    val s = vm.state
    val heading = vm.heading
    // Like Google Maps: only nag while the compass is in use and poor, until dismissed.
    val show = !s.calibrationDismissed && heading != null && heading.accuracy > 30 &&
        (s.follow == Follow.Compass || s.screen == Screen.Nav)
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        val p = LocalPalette.current
        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .background(p.surface, RoundedCornerShape(20.dp))
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                NText("Compass accuracy is low", NType.bodyStrong)
                NText("Move your phone in a figure 8 a few times.", NType.caption, color = p.muted)
            }
            RoundButton(Glyphs.Close, "Dismiss", vm::dismissCalibration, Modifier.size(40.dp), iconModifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun Shortcuts(vm: MapViewModel, rec: RecordState) {
    val s = vm.state
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (rec.status != RecStatus.Idle) item {
            val d = formatDistance(rec.distance)
            Pill("${formatClock(rec.movingMs)}   ${d.value} ${d.unit}", vm::openRecord, style = PillStyle.Filled, glyph = Glyphs.Record, height = 44.dp)
        }
        s.category?.let { c -> item { Pill("Clear ${c.label.lowercase()}", vm::clearNearby, style = PillStyle.Filled, glyph = Glyphs.Close, height = 44.dp) } }
        if (s.parking != null) item { Pill("Parking", { vm.onMarkerTap("parking") }, style = PillStyle.Tonal, glyph = Glyphs.Parking, height = 44.dp) }
        items(vm.share.friends.filter { it.last != null }, key = { "f" + it.link.topic }) { f ->
            Pill(f.label, { vm.openFriend(f) }, style = PillStyle.Tonal, glyph = Glyphs.Person, height = 44.dp)
        }
    }
}

/** Remembers the last non-null value so a sheet can keep showing its content while it animates out. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
