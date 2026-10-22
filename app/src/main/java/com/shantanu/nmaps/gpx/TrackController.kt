package com.shantanu.nmaps.gpx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shantanu.nmaps.data.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Imported GPX tracks: the library, the one being looked at, and waypoints waiting to be saved. */
class TrackController(private val context: Context) {
    private val store = TrackStore(context)

    var tracks by mutableStateOf(store.list())
        private set
    var selected by mutableStateOf<SavedTrack?>(null)
        private set
    var stats by mutableStateOf<TrackStats?>(null)
        private set
    var selectedPoints by mutableStateOf<List<GpxPoint>>(emptyList())
        private set

    /** Waypoints from the last imported file, offered for adding to saved places. */
    var pendingWaypoints by mutableStateOf<List<GpxWaypoint>>(emptyList())

    /** Lines for every track switched on in the library. */
    var visibleLines by mutableStateOf<List<List<LatLon>>>(emptyList())
        private set

    suspend fun refreshLines() {
        val shown = tracks.filter { it.visible }
        visibleLines = withContext(Dispatchers.IO) { shown.map { t -> store.points(t.id).map { it.latLon } } }
    }

    data class ImportResult(val tracks: List<SavedTrack>, val waypoints: Int)

    /** Reads a GPX file another app handed us or the user picked. Throws [Gpx.InvalidGpx] with a readable message. */
    suspend fun import(uri: Uri): ImportResult {
        val (file, name) = withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }?.substringBeforeLast('.') ?: "Imported track"
            val stream = context.contentResolver.openInputStream(uri) ?: throw Gpx.InvalidGpx("Couldn't open that file.")
            stream.use { Gpx.parse(it) } to name
        }
        if (file.isEmpty) throw Gpx.InvalidGpx("That GPX file has no tracks or waypoints.")
        val added = withContext(Dispatchers.IO) { store.add(file, name) }
        pendingWaypoints = file.waypoints
        tracks = store.list()
        refreshLines()
        return ImportResult(added, file.waypoints.size)
    }

    suspend fun select(track: SavedTrack?) {
        selected = track
        if (track == null) {
            stats = null
            selectedPoints = emptyList()
            return
        }
        val pts = withContext(Dispatchers.IO) { store.points(track.id) }
        selectedPoints = pts
        stats = withContext(Dispatchers.Default) { TrackStats.of(pts) }
    }

    suspend fun setVisible(track: SavedTrack, visible: Boolean) {
        store.setVisible(track.id, visible)
        tracks = store.list()
        refreshLines()
    }

    suspend fun delete(track: SavedTrack) {
        store.delete(track.id)
        tracks = store.list()
        if (selected?.id == track.id) select(null)
        refreshLines()
    }

    /** A copy of the original file in the shareable folder. */
    suspend fun exportFile(track: SavedTrack): File = withContext(Dispatchers.IO) {
        shareableGpx(context, track.name, store.file(track.id).readText())
    }
}
