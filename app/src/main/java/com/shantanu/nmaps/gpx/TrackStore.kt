package com.shantanu.nmaps.gpx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An imported GPX track in the library. */
data class SavedTrack(
    val id: Long,
    val name: String,
    val distance: Double,
    val gain: Double,
    val hasTimes: Boolean,
    val visible: Boolean,
)

/** Imported tracks: the original GPX file kept as-is plus a small index. */
class TrackStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("tracks", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "tracks").apply { mkdirs() }
    private val cache = HashMap<Long, List<GpxPoint>>()

    fun list(): List<SavedTrack> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedTrack(o.getLong("id"), o.getString("name"), o.getDouble("distance"), o.getDouble("gain"), o.optBoolean("times"), o.optBoolean("visible", true))
        }
    }.getOrDefault(emptyList())

    /** Adds every track in [file] (a multi-day GPX holds several) and returns what was added. */
    fun add(file: GpxFile, fallbackName: String): List<SavedTrack> {
        val now = System.currentTimeMillis()
        val added = file.tracks.mapIndexed { i, t ->
            val id = now + i
            val stats = TrackStats.of(t.points)
            val name = t.name?.takeIf { it.isNotBlank() } ?: file.name?.takeIf { it.isNotBlank() } ?: fallbackName
            File(dir, "$id.gpx").writeText(Gpx.write(name, listOf(t)))
            cache[id] = t.points
            SavedTrack(id, name, stats.distance, stats.gain, t.points.any { it.time != null }, visible = true)
        }
        write(added + list())
        return added
    }

    fun points(id: Long): List<GpxPoint> = cache.getOrPut(id) {
        runCatching { File(dir, "$id.gpx").inputStream().use { Gpx.parse(it) }.tracks.flatMap { it.points } }.getOrDefault(emptyList())
    }

    fun file(id: Long): File = File(dir, "$id.gpx")

    fun setVisible(id: Long, visible: Boolean) = write(list().map { if (it.id == id) it.copy(visible = visible) else it })

    fun rename(id: Long, name: String) = write(list().map { if (it.id == id) it.copy(name = name) else it })

    fun delete(id: Long) {
        File(dir, "$id.gpx").delete()
        cache.remove(id)
        write(list().filterNot { it.id == id })
    }

    private fun write(items: List<SavedTrack>) {
        val arr = JSONArray(items.map {
            JSONObject().put("id", it.id).put("name", it.name).put("distance", it.distance).put("gain", it.gain)
                .put("times", it.hasTimes).put("visible", it.visible)
        })
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "index"
    }
}

/** Writes GPX text into the cache folder that the FileProvider shares, named safely. */
fun shareableGpx(context: Context, baseName: String, body: String): File {
    val out = File(context.cacheDir, "gpx").apply { mkdirs() }
    val safe = baseName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
    return File(out, "$safe.gpx").apply { writeText(body) }
}
