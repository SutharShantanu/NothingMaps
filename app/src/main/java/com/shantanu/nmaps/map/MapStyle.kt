package com.shantanu.nmaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import com.shantanu.nmaps.data.BaseMap
import com.shantanu.nmaps.data.MapLayers
import org.maplibre.android.maps.Style

/**
 * The dot-matrix map: monochrome roads, halftone water and parks, dotted paths.
 * Vector tiles come from OpenFreeMap (OpenMapTiles schema), no API key needed.
 */
object MapStyle {
    const val SRC_ROUTE = "route"
    const val SRC_DEST = "dest"
    const val SRC_ME = "me"
    const val SRC_TRACK = "track"
    const val SRC_ALT = "alt"
    const val SRC_LINES = "lines"
    const val SRC_POINTS = "points"

    /** Heading beam images by compass accuracy, narrowest first. */
    const val BEAM_NARROW = "beam-narrow"
    const val BEAM_MEDIUM = "beam-medium"
    const val BEAM_WIDE = "beam-wide"
    val TAPPABLE_LAYERS = arrayOf("poi-label", "poi-dot", "place-minor", "place-city", "transit-label", "transit-stops")
    val MARKER_LAYERS = arrayOf("pt-ring", "pt-small", "pt-initial", "pt-label")
    val ME_LAYERS = arrayOf("me-dot", "me-halo")
    val ALT_LAYERS = arrayOf("alt-route")

    private const val RED = "#D71921"

    private data class Colors(
        val bg: String, val waterDots: Int, val parkDots: Int, val waterLine: String,
        val building: String, val buildingLine: String,
        val minor: String, val mid: String, val major: String, val motorway: String,
        val path: String, val rail: String, val boundary: String,
        val label: String, val labelStrong: String, val labelMuted: String, val halo: String,
        val poiDot: String, val puckRing: String, val casing: String, val pin: Int,
    )

    private val dark = Colors(
        bg = "#000000", waterDots = 0xFF4D4D4D.toInt(), parkDots = 0xFF2A2A2A.toInt(), waterLine = "#3A3A3A",
        building = "#0D0D0D", buildingLine = "#1F1F1F",
        minor = "#232323", mid = "#343434", major = "#505050", motorway = "#8C8C8C",
        path = "#3C3C3C", rail = "#333333", boundary = "#4A4A4A",
        label = "#8C8C8C", labelStrong = "#FFFFFF", labelMuted = "#5E5E5E", halo = "#000000",
        poiDot = "#5A5A5A", puckRing = "#FFFFFF", casing = "#000000", pin = 0xFFFFFFFF.toInt(),
    )

    private val light = Colors(
        bg = "#EFEFEF", waterDots = 0xFFA3A3A3.toInt(), parkDots = 0xFFCFCFCF.toInt(), waterLine = "#B3B3B3",
        building = "#E3E3E3", buildingLine = "#D3D3D3",
        minor = "#FFFFFF", mid = "#FFFFFF", major = "#C2C2C2", motorway = "#8F8F8F",
        path = "#B8B8B8", rail = "#C2C2C2", boundary = "#A0A0A0",
        label = "#666666", labelStrong = "#000000", labelMuted = "#999999", halo = "#EFEFEF",
        poiDot = "#A0A0A0", puckRing = "#FFFFFF", casing = "#FFFFFF", pin = 0xFF000000.toInt(),
    )

    fun builder(context: Context, isDark: Boolean, layers: MapLayers = MapLayers()): Style.Builder {
        val c = if (isDark) dark else light
        val density = context.resources.displayMetrics.density
        return Style.Builder()
            .fromJson(json(context, isDark, layers))
            .withImage("dots-water", halftone(6f * density, 1.15f * density, c.waterDots))
            .withImage("dots-park", halftone(8f * density, 0.95f * density, c.parkDots))
            .withImage(BEAM_NARROW, beam(56f * density, 34f))
            .withImage(BEAM_MEDIUM, beam(56f * density, 60f))
            .withImage(BEAM_WIDE, beam(56f * density, 100f))
            .withImage("nav-arrow", navArrow(34f * density, density))
            .withImage("dest-pin", dotPin(3.4f * density, 1.35f * density, c.pin))
    }

    /** A seamless tile of staggered dots: one in the centre, quarters in each corner. */
    private fun halftone(pitch: Float, radius: Float, color: Int): Bitmap {
        val size = pitch.toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bmp).apply {
            val s = size.toFloat()
            drawCircle(s / 2, s / 2, radius, paint)
            for (x in listOf(0f, s)) for (y in listOf(0f, s)) drawCircle(x, y, radius, paint)
        }
        return bmp
    }

    /** A wedge fading out from the centre, pointing north; rotated per heading by the style. */
    private fun beam(radius: Float, spreadDeg: Float): Bitmap {
        val size = (radius * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val red = 0xD71921
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(radius, radius, radius, intArrayOf(0x8C000000.toInt() or red, red), null, Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawArc(RectF(0f, 0f, size.toFloat(), size.toFloat()), -90f - spreadDeg / 2, spreadDeg, true, paint)
        return bmp
    }

    /** Navigation chevron: red with a white outline, pointing north. */
    private fun navArrow(size: Float, density: Float): Bitmap {
        val s = size.toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(size * 0.5f, size * 0.08f)
            lineTo(size * 0.86f, size * 0.9f)
            lineTo(size * 0.5f, size * 0.7f)
            lineTo(size * 0.14f, size * 0.9f)
            close()
        }
        Canvas(bmp).apply {
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() })
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * density; strokeJoin = Paint.Join.ROUND
            })
        }
        return bmp
    }

    /** The app icon's dot-matrix pin, used to mark the destination. Its tip is the bottom-centre pixel. */
    private fun dotPin(pitch: Float, radius: Float, color: Int): Bitmap {
        val rows = listOf("...XXX...", ".XXXXXXX.", "XXX...XXX", "XX..R..XX", "XXX...XXX", ".XXXXXXX.", "..XXXXX..", "...XXX...", "....X....")
        val size = (pitch * 9).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFD71921.toInt() }
        Canvas(bmp).apply {
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, ch ->
                    val paint = when (ch) { 'X' -> dot; 'R' -> red; else -> null } ?: return@forEachIndexed
                    drawCircle(pitch * (x + 0.5f), pitch * (y + 0.5f), if (ch == 'R') radius * 1.5f else radius, paint)
                }
            }
        }
        return bmp
    }

    // ---- style JSON -------------------------------------------------------------------------

    private fun j(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k as String, j(x)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(j(it)) } }
        else -> v
    }

    private fun zoom(vararg stops: Number) = listOf("interpolate", listOf("exponential", 1.5), listOf("zoom")) + stops.toList()
    private fun classIn(vararg classes: String) = listOf("match", listOf("get", "class"), classes.toList(), true, false)
    private fun kindIs(kind: String) = listOf("==", listOf("get", "kind"), kind)
    private val name = listOf("coalesce", listOf("get", "name_en"), listOf("get", "name:latin"), listOf("get", "name"))
    private val dotted = listOf(0, 2.2)
    private val emptyGeo = mapOf("type" to "geojson", "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()))
    private val roundLine = mapOf("line-cap" to "round", "line-join" to "round")

    private fun line(id: String, layer: String, filter: Any?, color: String, width: Any, dash: Any? = null, minzoom: Int = 0, opacity: Double = 1.0) = buildMap {
        put("id", id); put("type", "line"); put("source", "omt"); put("source-layer", layer)
        if (filter != null) put("filter", filter)
        put("minzoom", minzoom)
        put("layout", roundLine)
        put("paint", buildMap {
            put("line-color", color); put("line-width", width)
            if (dash != null) put("line-dasharray", dash)
            if (opacity < 1.0) put("line-opacity", opacity)
        })
    }

    private fun label(id: String, layer: String, filter: Any?, minzoom: Int, layout: Map<String, Any>, color: String, halo: String) = buildMap {
        put("id", id); put("type", "symbol"); put("source", "omt"); put("source-layer", layer); put("minzoom", minzoom)
        if (filter != null) put("filter", filter)
        put("layout", mapOf("text-field" to name, "text-font" to listOf("Noto Sans Regular"), "text-size" to 11, "text-max-width" to 8) + layout)
        put("paint", mapOf("text-color" to color, "text-halo-color" to halo, "text-halo-width" to 1.4))
    }

    private fun geoLine(id: String, source: String, filter: Any?, paint: Map<String, Any>) = buildMap {
        put("id", id); put("type", "line"); put("source", source); put("layout", roundLine); put("paint", paint)
        if (filter != null) put("filter", filter)
    }

    private fun circle(id: String, source: String, filter: Any?, paint: Map<String, Any>) = buildMap {
        put("id", id); put("type", "circle"); put("source", source); put("paint", paint)
        if (filter != null) put("filter", filter)
    }

    /** Style JSON for the given theme and layer choices. Offline downloads use the default dark one. */
    fun json(context: Context, isDark: Boolean, layers: MapLayers = MapLayers()): String {
        val sat = layers.base == BaseMap.Satellite
        // On imagery, everything drawn on top is light with a dark halo so it reads over any terrain.
        val c = when {
            sat -> dark.copy(label = "#E6E6E6", labelMuted = "#BDBDBD", labelStrong = "#FFFFFF", halo = "#000000")
            isDark -> dark
            else -> light
        }
        val roadOpacity = if (sat) 0.55 else 1.0

        val base = buildList {
            add(mapOf("id" to "bg", "type" to "background", "paint" to mapOf("background-color" to c.bg)))
            if (sat) {
                add(mapOf("id" to "satellite", "type" to "raster", "source" to "sat", "paint" to mapOf("raster-fade-duration" to 200)))
            } else {
                add(mapOf("id" to "landcover", "type" to "fill", "source" to "omt", "source-layer" to "landcover",
                    "filter" to classIn("wood", "grass", "wetland", "farmland"), "paint" to mapOf("fill-pattern" to "dots-park")))
                add(mapOf("id" to "park", "type" to "fill", "source" to "omt", "source-layer" to "park", "paint" to mapOf("fill-pattern" to "dots-park")))
                add(mapOf("id" to "water", "type" to "fill", "source" to "omt", "source-layer" to "water", "paint" to mapOf("fill-pattern" to "dots-water")))
                add(line("waterway", "waterway", null, c.waterLine, zoom(8, 0.6, 16, 3), dotted))
            }
            if (layers.terrain) {
                // Relief shading: on the black map only the lit slopes show, like light raking across a dot screen.
                add(mapOf("id" to "hillshade", "type" to "hillshade", "source" to "dem", "paint" to mapOf(
                    "hillshade-exaggeration" to if (isDark || sat) 0.45 else 0.35,
                    "hillshade-shadow-color" to if (isDark || sat) "#000000" else "#8A8A8A",
                    "hillshade-highlight-color" to if (isDark || sat) "#4A4A4A" else "#FFFFFF",
                    "hillshade-accent-color" to if (isDark || sat) "#000000" else "#9A9A9A",
                )))
            }
            if (!sat) {
                add(mapOf("id" to "building", "type" to "fill", "source" to "omt", "source-layer" to "building", "minzoom" to 14,
                    "maxzoom" to if (layers.buildings3d) 15 else 24,
                    "paint" to mapOf("fill-color" to c.building, "fill-outline-color" to c.buildingLine)))
            }
            add(line("path", "transportation", classIn("path", "track"), c.path, zoom(14, 1.2, 18, 3), dotted, minzoom = 14, opacity = roadOpacity))
            add(line("rail", "transportation", classIn("rail", "transit"), c.rail, zoom(10, 0.8, 18, 3), dotted, minzoom = 10, opacity = roadOpacity))
            if (!sat) add(line("road-minor", "transportation", classIn("minor", "service"), c.minor, zoom(12, 0.5, 16, 5, 20, 22), minzoom = 12))
            add(line("road-mid", "transportation", classIn("secondary", "tertiary"), if (sat) "#FFFFFF" else c.mid, zoom(8, 0.5, 12, 1.6, 16, 8, 20, 30), opacity = if (sat) 0.35 else 1.0))
            add(line("road-major", "transportation", classIn("primary", "trunk"), if (sat) "#FFFFFF" else c.major, zoom(6, 0.6, 12, 2.4, 16, 10, 20, 36), opacity = if (sat) 0.45 else 1.0))
            add(line("road-motorway", "transportation", classIn("motorway"), if (sat) "#FFFFFF" else c.motorway, zoom(5, 0.8, 12, 3, 16, 12, 20, 40), opacity = roadOpacity))
            add(line("boundary", "boundary",
                listOf("all", listOf("<=", listOf("get", "admin_level"), 4), listOf("!=", listOf("get", "maritime"), 1)),
                c.boundary, zoom(3, 0.8, 10, 1.6), dotted))

            if (layers.cycling) {
                // Cycleways and roads marked for bikes, drawn bright over the grey network.
                add(line("cycling", "transportation",
                    listOf("any",
                        listOf("==", listOf("get", "subclass"), "cycleway"),
                        listOf("match", listOf("get", "bicycle"), listOf("designated", "yes"), true, false)),
                    c.labelStrong, zoom(11, 1.0, 16, 3.5, 20, 8), minzoom = 11, opacity = 0.85))
            }
            if (layers.transit) {
                add(line("transit-lines", "transportation", classIn("rail", "transit"), c.labelStrong, zoom(9, 1.0, 14, 2.4, 18, 4), minzoom = 9))
                add(mapOf("id" to "transit-stops", "type" to "circle", "source" to "omt", "source-layer" to "poi", "minzoom" to 12,
                    "filter" to classIn("railway", "bus"),
                    "paint" to mapOf(
                        "circle-radius" to listOf("match", listOf("get", "class"), "railway", 5, 2.5),
                        "circle-color" to c.bg, "circle-stroke-color" to c.labelStrong, "circle-stroke-width" to 2,
                    )))
                add(label("transit-label", "poi", classIn("railway"), 12,
                    mapOf("text-font" to listOf("Noto Sans Bold"), "text-anchor" to "top", "text-offset" to listOf(0, 0.8)),
                    c.labelStrong, c.halo))
            }
            if (layers.buildings3d && !sat) {
                add(mapOf("id" to "building-3d", "type" to "fill-extrusion", "source" to "omt", "source-layer" to "building", "minzoom" to 15,
                    "filter" to listOf("!=", listOf("get", "hide_3d"), true),
                    "paint" to mapOf(
                        "fill-extrusion-color" to if (isDark) "#161616" else "#DCDCDC",
                        "fill-extrusion-height" to listOf("coalesce", listOf("get", "render_height"), 6),
                        "fill-extrusion-base" to listOf("coalesce", listOf("get", "render_min_height"), 0),
                        "fill-extrusion-opacity" to 0.9,
                    )))
            }
        }

        val overlays = listOf(
            // Alternative routes sit under the chosen one, in grey; tap to switch.
            geoLine("alt-casing", SRC_ALT, null, mapOf("line-color" to c.casing, "line-width" to zoom(10, 6, 14, 9, 18, 15))),
            geoLine("alt-route", SRC_ALT, null, mapOf("line-color" to c.label, "line-width" to zoom(10, 3, 14, 5, 18, 9))),
            // Imported GPX tracks and the timeline's path for a day.
            geoLine("lines-casing", SRC_LINES, kindIs("track"), mapOf("line-color" to c.casing, "line-width" to zoom(10, 5, 16, 8))),
            geoLine("lines-track", SRC_LINES, kindIs("track"), mapOf("line-color" to c.labelStrong, "line-width" to zoom(10, 2.5, 16, 4.5))),
            geoLine("lines-history", SRC_LINES, kindIs("history"), mapOf("line-color" to RED, "line-width" to zoom(10, 3, 16, 5), "line-dasharray" to listOf(0, 1.8))),
            geoLine("track", SRC_TRACK, null, mapOf("line-color" to c.labelStrong, "line-width" to zoom(10, 2.5, 16, 4.5, 20, 8), "line-opacity" to 0.9)),
            geoLine("route-casing", SRC_ROUTE, listOf("!", listOf("get", "dotted")), mapOf("line-color" to c.casing, "line-width" to zoom(10, 6, 14, 9, 18, 15))),
            geoLine("route-line", SRC_ROUTE, listOf("!", listOf("get", "dotted")), mapOf("line-color" to RED, "line-width" to zoom(10, 3, 14, 5, 18, 9))),
            geoLine("route-dots", SRC_ROUTE, listOf("get", "dotted"), mapOf("line-color" to RED, "line-width" to zoom(10, 4, 14, 6, 18, 9), "line-dasharray" to listOf(0, 1.8))),
        )

        val labels = listOf(
            label("water-name", "water_name", null, 10, mapOf("text-font" to listOf("Noto Sans Italic"), "text-letter-spacing" to 0.1), c.labelMuted, c.halo),
            label("road-label", "transportation_name", null, 14, mapOf("symbol-placement" to "line", "text-size" to 11, "text-letter-spacing" to 0.05), c.label, c.halo),
            mapOf("id" to "poi-dot", "type" to "circle", "source" to "omt", "source-layer" to "poi", "minzoom" to 15,
                "filter" to listOf("has", "name"), "paint" to mapOf("circle-radius" to 2.5, "circle-color" to c.poiDot)),
            label("poi-label", "poi", listOf("has", "name"), 15,
                mapOf("text-anchor" to "left", "text-offset" to listOf(0.7, 0), "text-justify" to "left", "symbol-sort-key" to listOf("get", "rank")),
                c.label, c.halo),
            label("place-minor", "place", classIn("suburb", "neighbourhood", "quarter", "village", "hamlet"), 11,
                mapOf("text-transform" to "uppercase", "text-letter-spacing" to 0.2, "text-size" to 10), c.labelMuted, c.halo),
            label("place-city", "place", classIn("city", "town"), 4,
                mapOf("text-transform" to "uppercase", "text-letter-spacing" to 0.18, "text-font" to listOf("Noto Sans Bold"), "text-size" to zoom(4, 10, 12, 16)),
                c.labelStrong, c.halo),
            label("place-country", "place", classIn("country"), 1,
                mapOf("text-transform" to "uppercase", "text-letter-spacing" to 0.3, "text-size" to 11, "text-font" to listOf("Noto Sans Bold")),
                c.label, c.halo).plus("maxzoom" to 7),
        )

        val markers = listOf(
            // Points: friends, parking, track ends, nearby results, timeline visits.
            circle("pt-ring", SRC_POINTS, listOf("match", listOf("get", "kind"), listOf("friend", "parking"), true, false),
                mapOf("circle-radius" to 14, "circle-color" to c.labelStrong, "circle-stroke-width" to 2.5, "circle-stroke-color" to RED)),
            circle("pt-small", SRC_POINTS, listOf("match", listOf("get", "kind"), listOf("nearby", "visit", "start", "end", "stop"), true, false),
                mapOf(
                    "circle-radius" to listOf("match", listOf("get", "kind"), "nearby", 6, 7),
                    "circle-color" to listOf("match", listOf("get", "kind"), listOf("nearby", "end"), RED, c.labelStrong),
                    "circle-stroke-width" to 2.5, "circle-stroke-color" to c.casing,
                )),
            mapOf("id" to "pt-initial", "type" to "symbol", "source" to SRC_POINTS,
                "filter" to listOf("match", listOf("get", "kind"), listOf("friend", "parking"), true, false),
                "layout" to mapOf("text-field" to listOf("get", "initial"), "text-font" to listOf("Noto Sans Bold"), "text-size" to 14,
                    "text-allow-overlap" to true, "text-ignore-placement" to true),
                "paint" to mapOf("text-color" to c.bg)),
            mapOf("id" to "pt-label", "type" to "symbol", "source" to SRC_POINTS,
                "filter" to listOf("has", "label"),
                "layout" to mapOf("text-field" to listOf("get", "label"), "text-font" to listOf("Noto Sans Bold"), "text-size" to 12,
                    "text-anchor" to "top", "text-offset" to listOf(0, 1.3), "text-max-width" to 9, "text-optional" to true),
                "paint" to mapOf("text-color" to c.labelStrong, "text-halo-color" to c.halo, "text-halo-width" to 1.6)),

            mapOf("id" to "dest-pin", "type" to "symbol", "source" to SRC_DEST,
                "layout" to mapOf(
                    "icon-image" to "dest-pin", "icon-anchor" to "bottom", "icon-allow-overlap" to true, "icon-ignore-placement" to true,
                    "text-field" to listOf("get", "name"), "text-font" to listOf("Noto Sans Bold"), "text-size" to 13,
                    "text-anchor" to "top", "text-offset" to listOf(0, 0.3), "text-max-width" to 10, "text-optional" to true,
                ),
                "paint" to mapOf("text-color" to c.labelStrong, "text-halo-color" to c.halo, "text-halo-width" to 1.6)),

            // You: accuracy disc, compass beam, soft halo, red dot with a white ring.
            // While navigating the dot becomes a chevron pointing along the direction of travel.
            circle("me-accuracy", SRC_ME, listOf("!", listOf("get", "nav")), mapOf(
                "circle-radius" to listOf("interpolate", listOf("exponential", 2), listOf("zoom"), 0, listOf("get", "acc0"), 22, listOf("*", listOf("get", "acc0"), 4194304)),
                "circle-color" to RED, "circle-opacity" to 0.07, "circle-pitch-alignment" to "map",
                "circle-stroke-width" to 1, "circle-stroke-color" to RED, "circle-stroke-opacity" to 0.25)),
            mapOf("id" to "me-beam", "type" to "symbol", "source" to SRC_ME,
                "filter" to listOf("all", listOf("has", "heading"), listOf("!", listOf("get", "nav"))),
                "layout" to mapOf("icon-image" to listOf("get", "beam"), "icon-rotate" to listOf("get", "heading"),
                    "icon-rotation-alignment" to "map", "icon-pitch-alignment" to "map", "icon-allow-overlap" to true, "icon-ignore-placement" to true)),
            circle("me-halo", SRC_ME, listOf("!", listOf("get", "nav")), mapOf("circle-radius" to 16, "circle-color" to RED, "circle-opacity" to 0.2)),
            circle("me-dot", SRC_ME, listOf("!", listOf("get", "nav")),
                mapOf("circle-radius" to 7, "circle-color" to RED, "circle-stroke-width" to 3, "circle-stroke-color" to c.puckRing)),
            mapOf("id" to "me-arrow", "type" to "symbol", "source" to SRC_ME, "filter" to listOf("get", "nav"),
                "layout" to mapOf("icon-image" to "nav-arrow", "icon-rotate" to listOf("get", "course"),
                    "icon-rotation-alignment" to "map", "icon-pitch-alignment" to "map", "icon-allow-overlap" to true, "icon-ignore-placement" to true)),
        )

        val sources = buildMap<String, Any> {
            put("omt", mapOf("type" to "vector", "url" to "https://tiles.openfreemap.org/planet",
                "attribution" to "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"))
            if (sat) put("sat", mapOf("type" to "raster", "tileSize" to 256, "maxzoom" to 19,
                "tiles" to listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
                "attribution" to "Imagery © Esri, Maxar, Earthstar Geographics"))
            if (layers.terrain) put("dem", mapOf("type" to "raster-dem", "encoding" to "terrarium", "tileSize" to 256, "maxzoom" to 15,
                "tiles" to listOf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"),
                "attribution" to "Terrain: Mapzen, AWS Open Data"))
            listOf(SRC_ROUTE, SRC_DEST, SRC_ME, SRC_TRACK, SRC_ALT, SRC_LINES, SRC_POINTS).forEach { put(it, emptyGeo) }
        }

        val style = mapOf(
            "version" to 8,
            "name" to "dot-matrix",
            "glyphs" to "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "sources" to sources,
            "layers" to base + overlays + labels + markers,
        )
        return (j(style) as JSONObject).toString()
    }
}
