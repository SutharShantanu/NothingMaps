package com.shantanu.nmaps.map

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.MapLayers
import com.shantanu.nmaps.offline.Bounds
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.data.Route
import com.shantanu.nmaps.data.Travel
import com.shantanu.nmaps.sensors.Heading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.VectorSource
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import kotlin.math.cos

sealed interface CameraCommand {
    data class FlyTo(val at: LatLon, val zoom: Double) : CameraCommand
    /** Fit these points, keeping clear of overlays (in dp) at the top and bottom. */
    data class Fit(val points: List<LatLon>, val topDp: Int, val bottomDp: Int) : CameraCommand
    data object North : CameraCommand
    data class ZoomBy(val delta: Double) : CameraCommand
    /** Places of these OSM subclasses in the loaded map tiles; answers on [result]. */
    data class QueryPois(val subclasses: List<String>, val result: CompletableDeferred<List<Place>>) : CameraCommand
}

/** One of the app's own markers. [id] comes back through onMarkerTap; [kind] picks its look in the style. */
data class MapMarker(val id: String, val kind: String, val at: LatLon, val label: String? = null, val initial: String? = null)

/** Everything drawn on top of the base map besides you, the route and the destination. */
data class MapOverlays(
    /** Other route options with their index in the route list, so a tap can pick one. */
    val alternatives: List<Pair<Int, List<LatLon>>> = emptyList(),
    val trackLines: List<List<LatLon>> = emptyList(),
    /** Red dotted lines: the timeline's path, or the walking parts of a transit trip — one line each. */
    val history: List<List<LatLon>> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
)

/**
 * How the camera follows the user, mirroring the locate button in Google and Apple Maps:
 * not at all, centred north-up, centred and turning with the phone's compass, or the tilted navigation view.
 */
enum class Follow { None, Flat, Compass, Nav }

@Composable
fun DotMap(
    dark: Boolean,
    layers: MapLayers,
    fix: Fix?,
    route: Route?,
    destination: Place?,
    follow: Follow,
    heading: () -> Heading?,
    track: List<LatLon>?,
    overlays: MapOverlays,
    commands: Flow<CameraCommand>,
    onBearing: (Float) -> Unit,
    onUserPan: () -> Unit,
    onTap: (name: String, at: LatLon) -> Unit,
    onLongPress: (LatLon) -> Unit,
    onMarkerTap: (String) -> Unit,
    onMeTap: () -> Unit,
    onAlternativeTap: (Int) -> Unit,
    onCameraIdle: (Bounds) -> Unit,
    onReady: () -> Unit = {},
    onCameraMove: (LatLon, Float) -> Unit = { _, _ -> },
    rotateGestures: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val pan by rememberUpdatedState(onUserPan)
    val bearing by rememberUpdatedState(onBearing)
    val markerTap by rememberUpdatedState(onMarkerTap)
    val meTap by rememberUpdatedState(onMeTap)
    val altTap by rememberUpdatedState(onAlternativeTap)
    val idle by rememberUpdatedState(onCameraIdle)
    val moved by rememberUpdatedState(onCameraMove)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) pan()
            }
            m.addOnCameraMoveListener {
                val cam = m.cameraPosition
                bearing(cam.bearing.toFloat())
                cam.target?.let { moved(LatLon(it.latitude, it.longitude), cam.zoom.toFloat()) }
            }
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                idle(Bounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
            }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val r = 14 * density
                val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
                // Our own things first: markers, then you, then a grey route option, then map labels.
                m.queryRenderedFeatures(box, *MapStyle.MARKER_LAYERS).firstOrNull { it.hasProperty("id") }?.let {
                    markerTap(it.getStringProperty("id"))
                    return@addOnMapClickListener true
                }
                if (m.queryRenderedFeatures(box, *MapStyle.ME_LAYERS).isNotEmpty()) {
                    meTap()
                    return@addOnMapClickListener true
                }
                m.queryRenderedFeatures(box, *MapStyle.ALT_LAYERS).firstOrNull { it.hasProperty("index") }?.let {
                    altTap(it.getNumberProperty("index").toInt())
                    return@addOnMapClickListener true
                }
                val hit = m.queryRenderedFeatures(box, *MapStyle.TAPPABLE_LAYERS)
                    .firstOrNull { !it.getStringProperty("name").isNullOrBlank() }
                    ?: return@addOnMapClickListener false
                val at = (hit.geometry() as? Point)?.let { LatLon(it.latitude(), it.longitude()) } ?: LatLon(ll.latitude, ll.longitude)
                val label = if (hit.hasProperty("name_en")) hit.getStringProperty("name_en") else hit.getStringProperty("name")
                tap(label, at)
                true
            }
            m.addOnMapLongClickListener { ll ->
                longPress(LatLon(ll.latitude, ll.longitude))
                true
            }
            map = m
        }
    }

    LaunchedEffect(map, rotateGestures) {
        map?.uiSettings?.isRotateGesturesEnabled = rotateGestures
    }

    LaunchedEffect(map, dark, layers) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(MapStyle.builder(context, dark, layers)) {
            style = it
            onReady()
        }
    }

    LaunchedEffect(style, overlays) {
        val st = style ?: return@LaunchedEffect
        st.push(MapStyle.SRC_ALT, collection(overlays.alternatives.map { (i, pts) -> lineFeature(pts, JSONObject().put("index", i)) }))
        st.push(MapStyle.SRC_LINES, collection(
            overlays.trackLines.map { lineFeature(it, JSONObject().put("kind", "track")) } +
                overlays.history.map { lineFeature(it, JSONObject().put("kind", "history")) }
        ))
        st.push(MapStyle.SRC_POINTS, collection(overlays.markers.map { mk ->
            val props = JSONObject().put("id", mk.id).put("kind", mk.kind)
            mk.label?.let { props.put("label", it) }
            mk.initial?.let { props.put("initial", it) }
            JSONObject(point(mk.at.lat, mk.at.lon, props))
        }))
    }

    // The puck redraws on every compass change without recomposing anything.
    LaunchedEffect(style, fix, follow) {
        if (style == null) return@LaunchedEffect
        // Read `style` on every emission: a theme switch replaces it before this effect is cancelled.
        snapshotFlow { heading() }.collect { style?.push(MapStyle.SRC_ME, meGeoJson(fix, it, follow == Follow.Nav)) }
    }
    LaunchedEffect(style, track) { style?.push(MapStyle.SRC_TRACK, lineGeoJson(track, JSONObject())) }
    LaunchedEffect(style, route) { style?.push(MapStyle.SRC_ROUTE, routeGeoJson(route)) }
    LaunchedEffect(style, destination) { style?.push(MapStyle.SRC_DEST, destGeoJson(destination)) }

    LaunchedEffect(map, fix, follow) {
        val m = map ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        val target = LatLng(f.lat, f.lon)
        when (follow) {
            Follow.None -> Unit
            Follow.Flat -> m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(target).zoom(m.cameraPosition.zoom.coerceAtLeast(15.0))
                        .bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                ),
                900,
            )
            // Map turns with the phone; short animations keep it smooth at sensor rate.
            Follow.Compass -> snapshotFlow { heading()?.degrees }.filterNotNull().collect { deg ->
                m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(target).zoom(m.cameraPosition.zoom.coerceAtLeast(16.0))
                            .bearing(deg.toDouble()).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                    ),
                    160,
                )
            }
            // Heading-up and tilted, puck low on screen. GPS course while moving; the compass when stopped, as GPS has no course then.
            Follow.Nav -> {
                fun nav(bearing: Double, ms: Int) = m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(target).zoom(17.0).tilt(50.0).bearing(bearing)
                            .padding(0.0, mapView.height * 0.45, 0.0, 0.0).build()
                    ),
                    ms,
                )
                val course = f.bearing
                if (course != null) {
                    nav(course.toDouble(), 900)
                } else {
                    nav(heading()?.degrees?.toDouble() ?: m.cameraPosition.bearing, 900)
                    var last = 0L
                    snapshotFlow { heading()?.degrees }.filterNotNull().collect { deg ->
                        val now = System.currentTimeMillis()
                        if (now - last > 300) {
                            last = now
                            nav(deg.toDouble(), 300)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        commands.collect { cmd ->
            when (cmd) {
                is CameraCommand.FlyTo -> m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(cmd.at.lat, cmd.at.lon)).zoom(cmd.zoom).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
                    ),
                    1200,
                )
                is CameraCommand.Fit -> {
                    if (cmd.points.size < 2) return@collect
                    val bounds = LatLngBounds.Builder().includes(cmd.points.map { LatLng(it.lat, it.lon) }).build()
                    val side = (32 * density).toInt()
                    m.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, side, (cmd.topDp * density).toInt(), side, (cmd.bottomDp * density).toInt()),
                        1000,
                    )
                }
                CameraCommand.North -> m.animateCamera(CameraUpdateFactory.bearingTo(0.0), 500)
                is CameraCommand.ZoomBy -> m.animateCamera(CameraUpdateFactory.zoomBy(cmd.delta), 250)
                is CameraCommand.QueryPois -> cmd.result.complete(queryPois(style, cmd.subclasses))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Sets a GeoJSON source's data. A style that has just been replaced throws "invalid native peer";
 * the new style gets the same data from its own effects, so dropping this write is safe.
 */
private fun Style.push(id: String, json: String) {
    try {
        getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
    } catch (_: IllegalStateException) {
    }
}

private fun queryPois(style: Style?, subclasses: List<String>): List<Place> {
    val src = style?.getSourceAs<VectorSource>("omt") ?: return emptyList()
    val filter = Expression.raw(JSONArray(listOf("match", JSONArray(listOf("get", "subclass")), JSONArray(subclasses), true, false)).toString())
    return src.querySourceFeatures(arrayOf("poi"), filter).mapNotNull { f ->
        val p = f.geometry() as? Point ?: return@mapNotNull null
        val name = (if (f.hasProperty("name_en")) f.getStringProperty("name_en") else null)
            ?: (if (f.hasProperty("name")) f.getStringProperty("name") else null)
            ?: return@mapNotNull null
        Place(name, "", p.latitude(), p.longitude())
    }.distinctBy { it.name + "%.3f%.3f".format(java.util.Locale.US, it.lat, it.lon) }
}

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

private fun point(lat: Double, lon: Double, props: JSONObject = JSONObject()) = JSONObject()
    .put("type", "Feature")
    .put("properties", props)
    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
    .toString()

private fun meGeoJson(fix: Fix?, heading: Heading?, nav: Boolean): String {
    fix ?: return EMPTY
    // Accuracy radius in pixels at zoom 0 (512 px tiles); the style scales it per zoom.
    val metersPerPixelZ0 = 78_271.517 * cos(Math.toRadians(fix.lat))
    val props = JSONObject().put("acc0", fix.accuracy / metersPerPixelZ0).put("nav", nav)
    if (heading != null) {
        props.put("heading", heading.degrees)
        props.put(
            "beam",
            when {
                heading.accuracy <= 15 -> MapStyle.BEAM_NARROW
                heading.accuracy <= 30 -> MapStyle.BEAM_MEDIUM
                else -> MapStyle.BEAM_WIDE
            },
        )
    }
    props.put("course", fix.bearing ?: heading?.degrees ?: 0f)
    return point(fix.lat, fix.lon, props)
}

private fun destGeoJson(place: Place?): String =
    place?.let { point(it.lat, it.lon, JSONObject().put("name", it.name)) } ?: EMPTY

private fun routeGeoJson(route: Route?): String =
    lineGeoJson(route?.points, JSONObject().put("dotted", route?.travel == Travel.Walk))

private fun lineGeoJson(points: List<LatLon>?, props: JSONObject): String =
    lineFeature(points, props)?.toString() ?: EMPTY

private fun lineFeature(points: List<LatLon>?, props: JSONObject): JSONObject? {
    if (points == null || points.size < 2) return null
    val coords = JSONArray()
    points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
    return JSONObject()
        .put("type", "Feature")
        .put("properties", props)
        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
}

private fun collection(features: List<JSONObject?>): String =
    JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features.filterNotNull())).toString()
