package com.shantanu.nmaps.record

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.shantanu.nmaps.gpx.Gpx
import com.shantanu.nmaps.gpx.GpxPoint
import com.shantanu.nmaps.gpx.GpxTrack
import com.shantanu.nmaps.gpx.shareableGpx
import java.io.File
import java.util.Calendar

data class ActivitySummary(
    val id: Long,
    val name: String,
    val sport: Sport,
    val startedAt: Long,
    val movingMs: Long,
    val distance: Double,
    val elevationGain: Double,
    val steps: Int,
)

/** Recorded activities: a small index in preferences plus one JSON file of track points each. */
class ActivityStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("activities", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "activities").apply { mkdirs() }

    fun list(): List<ActivitySummary> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toSummary() }
    }.getOrDefault(emptyList())

    fun save(a: RecordedActivity): ActivitySummary {
        val summary = ActivitySummary(a.startedAt, defaultName(a.sport, a.startedAt), a.sport, a.startedAt, a.movingMs, a.distance, a.elevationGain, a.steps)
        val pts = JSONArray()
        a.points.forEach { p ->
            pts.put(JSONArray().put(p.lat).put(p.lon).put(p.ele ?: JSONObject.NULL).put(p.time))
        }
        File(dir, "${summary.id}.json").writeText(pts.toString())
        writeIndex(listOf(summary) + list().filterNot { it.id == summary.id })
        return summary
    }

    fun delete(id: Long) {
        File(dir, "$id.json").delete()
        writeIndex(list().filterNot { it.id == id })
    }

    fun points(id: Long): List<TrackPoint> = runCatching {
        val arr = JSONArray(File(dir, "$id.json").readText())
        (0 until arr.length()).map {
            val p = arr.getJSONArray(it)
            TrackPoint(p.getDouble(0), p.getDouble(1), if (p.isNull(2)) null else p.getDouble(2), p.getLong(3))
        }
    }.getOrDefault(emptyList())

    /** Writes a GPX 1.1 file (what Strava, Garmin and Komoot import) into the shareable cache folder. */
    fun gpx(summary: ActivitySummary): File {
        val track = GpxTrack(summary.name, summary.sport.gpxType, points(summary.id).map { GpxPoint(it.lat, it.lon, it.ele, it.time) })
        return shareableGpx(context, "${summary.name}_${summary.id}", Gpx.write(summary.name, listOf(track)))
    }

    private fun writeIndex(items: List<ActivitySummary>) {
        prefs.edit().putString(KEY, JSONArray(items.map { it.toJson() }).toString()).apply()
    }

    private fun ActivitySummary.toJson() = JSONObject()
        .put("id", id).put("name", name).put("sport", sport.name).put("startedAt", startedAt)
        .put("movingMs", movingMs).put("distance", distance).put("gain", elevationGain).put("steps", steps)

    private fun JSONObject.toSummary() = ActivitySummary(
        getLong("id"), getString("name"), Sport.valueOf(getString("sport")), getLong("startedAt"),
        getLong("movingMs"), getDouble("distance"), getDouble("gain"), optInt("steps"),
    )

    private companion object {
        const val KEY = "index"

        /** "Morning Run", "Evening Ride" — the naming Strava users know. */
        fun defaultName(sport: Sport, at: Long): String {
            val hour = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
            val part = when (hour) {
                in 5..11 -> "Morning"
                in 12..16 -> "Afternoon"
                in 17..20 -> "Evening"
                else -> "Night"
            }
            return "$part ${sport.label}"
        }
    }
}
