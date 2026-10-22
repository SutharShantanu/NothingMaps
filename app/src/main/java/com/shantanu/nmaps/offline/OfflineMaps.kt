package com.shantanu.nmaps.offline

import android.content.Context
import com.shantanu.nmaps.data.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center get() = LatLon((south + north) / 2, (west + east) / 2)
}

data class OfflineArea(
    val id: Long,
    val name: String,
    val bounds: Bounds,
    val createdAt: Long,
    val bytes: Long = 0,
    val progress: Float = 0f,
    val complete: Boolean = false,
    val downloading: Boolean = false,
    val error: String? = null,
)

/**
 * Downloaded map areas. The vector tiles stop at zoom 14 and are over-zoomed beyond that, so an
 * area is every tile from zoom 0 to 14 — small enough that a whole city is a few tens of MB.
 * Tiles are shared between the light and dark map because both styles use the same sources.
 */
class OfflineMaps(private val context: Context) {
    private val manager by lazy {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context).apply { setOfflineMapboxTileCountLimit(MAX_TILES) }
    }
    private val regions = HashMap<Long, OfflineRegion>()
    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas.asStateFlow()

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty().mapNotNull { r ->
                    regions[r.id] = r
                    val meta = runCatching { JSONObject(String(r.metadata)) }.getOrNull() ?: return@mapNotNull null
                    val b = (r.definition as? OfflineTilePyramidRegionDefinition)?.bounds ?: return@mapNotNull null
                    OfflineArea(r.id, meta.optString("name", "Area"), Bounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast), meta.optLong("created"))
                }.sortedByDescending { it.createdAt }
                _areas.value = list
                list.forEach { area -> regions[area.id]?.let { observe(it); it.getStatus(statusCallback(area.id)) } }
            }

            override fun onError(error: String) = Unit
        })
    }

    fun download(name: String, bounds: Bounds, pixelRatio: Float) {
        val definition = OfflineTilePyramidRegionDefinition(
            DOWNLOAD_STYLE,
            LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
            0.0, MAX_ZOOM.toDouble(), pixelRatio, false,
        )
        val meta = JSONObject().put("name", name).put("created", System.currentTimeMillis()).toString().toByteArray()
        manager.createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regions[offlineRegion.id] = offlineRegion
                _areas.update { listOf(OfflineArea(offlineRegion.id, name, bounds, System.currentTimeMillis(), downloading = true)) + it }
                observe(offlineRegion)
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _areas.update { listOf(OfflineArea(-System.currentTimeMillis(), name, bounds, System.currentTimeMillis(), error = error)) + it }
            }
        })
    }

    fun resume(id: Long) {
        regions[id]?.setDownloadState(OfflineRegion.STATE_ACTIVE)
        _areas.update { list -> list.map { if (it.id == id) it.copy(downloading = true, error = null) else it } }
    }

    fun delete(id: Long) {
        val region = regions.remove(id)
        if (region == null) {
            _areas.update { list -> list.filterNot { it.id == id } }
            return
        }
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = _areas.update { list -> list.filterNot { it.id == id } }
            override fun onError(error: String) = Unit
        })
    }

    private fun observe(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = apply(region.id, status)

            override fun onError(error: OfflineRegionError) {
                // Transient network errors retry on their own; only surface them.
                _areas.update { list -> list.map { if (it.id == region.id) it.copy(error = "Download paused: ${error.reason.lowercase()}") else it } }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                _areas.update { list -> list.map { if (it.id == region.id) it.copy(downloading = false, error = "Area is too large. Zoom in and download a smaller one.") else it } }
            }
        })
    }

    private fun statusCallback(id: Long) = object : OfflineRegion.OfflineRegionStatusCallback {
        override fun onStatus(status: OfflineRegionStatus?) {
            status?.let { apply(id, it) }
        }

        override fun onError(error: String?) = Unit
    }

    private fun apply(id: Long, status: OfflineRegionStatus) {
        val required = status.requiredResourceCount.coerceAtLeast(1)
        val complete = status.isComplete
        if (complete) regions[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        _areas.update { list ->
            list.map {
                if (it.id != id) it else it.copy(
                    bytes = status.completedResourceSize,
                    progress = status.completedResourceCount.toFloat() / required,
                    complete = complete,
                    downloading = !complete && status.downloadState == OfflineRegion.STATE_ACTIVE,
                    error = if (complete) null else it.error,
                )
            }
        }
    }

    companion object {
        /**
         * The downloader only fetches http(s) styles. OpenFreeMap's Positron uses the very same tile
         * source and font stacks as the dot-matrix style, so what it stores is exactly what our map asks for.
         */
        private const val DOWNLOAD_STYLE = "https://tiles.openfreemap.org/styles/positron"
        const val MAX_ZOOM = 14
        const val MAX_TILES = 60_000L
        private const val AVG_TILE_BYTES = 45_000L
        /** Glyph ranges for the three Noto Sans stacks, measured from a real first download. */
        const val FONT_BYTES = 9_500_000L

        /** Tiles needed for [b] at zooms 0..14, the same pyramid MapLibre will fetch. */
        fun tileCount(b: Bounds): Long = (0..MAX_ZOOM).sumOf { z ->
            val n = 2.0.pow(z)
            fun x(lon: Double) = floor((lon + 180) / 360 * n).toLong()
            fun y(lat: Double): Long {
                val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
                return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toLong()
            }
            (x(b.east) - x(b.west) + 1) * (y(b.south) - y(b.north) + 1)
        }

        fun estimatedBytes(b: Bounds) = tileCount(b) * AVG_TILE_BYTES
    }
}
