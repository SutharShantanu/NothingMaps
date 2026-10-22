package com.shantanu.nmaps.timeline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.Geocoder
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.data.Place
import com.shantanu.nmaps.gpx.Gpx
import com.shantanu.nmaps.gpx.GpxPoint
import com.shantanu.nmaps.gpx.GpxTrack
import com.shantanu.nmaps.gpx.shareableGpx
import com.shantanu.nmaps.health.HealthConnect
import com.shantanu.nmaps.health.HealthDay
import com.shantanu.nmaps.nav.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TimelineUi(
    val dayStart: Long,
    val segments: List<Segment> = emptyList(),
    val path: List<LatLon> = emptyList(),
    /** Visit index → place name. */
    val names: Map<Int, String> = emptyMap(),
    val loading: Boolean = false,
    /** Month view instead of the day list. */
    val month: MonthSummary? = null,
    val monthNames: Map<Int, String> = emptyMap(),
    /** Likely home and work from history, offered when those shortcuts aren't set. */
    val suggestedHome: LatLon? = null,
    val suggestedWork: LatLon? = null,
    /** Steps and workouts from Health Connect for the day, when that's switched on. */
    val health: HealthDay? = null,
)

class TimelineController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val savedPlaces: () -> List<Place>,
    /** Whether Home and Work are already set, so they aren't suggested again. */
    private val shortcutsSet: () -> Pair<Boolean, Boolean> = { false to false },
    private val healthEnabled: () -> Boolean = { false },
) {
    private val db = HistoryDb.get(context)
    private var loadJob: Job? = null

    var ui by mutableStateOf(TimelineUi(startOfDay(System.currentTimeMillis())))
        private set

    val isToday get() = ui.dayStart >= startOfDay(System.currentTimeMillis())

    fun record(fix: Fix) {
        scope.launch(Dispatchers.IO) { db.add(HistoryPoint(fix.time, fix.lat, fix.lon, fix.accuracy, fix.speed), thin = true) }
    }

    fun load(dayStart: Long = ui.dayStart) {
        loadJob?.cancel()
        ui = ui.copy(dayStart = dayStart, segments = emptyList(), path = emptyList(), names = emptyMap(), loading = true, month = null, health = null)
        loadJob = scope.launch {
            if (healthEnabled()) launch {
                // Read `ui` only after the call returns, or this would overwrite the day load with a stale copy.
                val health = HealthConnect.day(context, dayStart, nextDay(dayStart))
                if (ui.dayStart == dayStart) ui = ui.copy(health = health)
            }
            val points = withContext(Dispatchers.IO) { db.range(dayStart, nextDay(dayStart)) }
            val modes = withContext(Dispatchers.IO) { db.tripModes(dayStart, nextDay(dayStart)) }
            // The user's own corrections override the guessed travel mode.
            val segments = withContext(Dispatchers.Default) { TimelineBuilder.segments(points) }.map { seg ->
                if (seg is Trip) modes[seg.start]?.let { m -> runCatching { seg.copy(mode = TripMode.valueOf(m)) }.getOrNull() } ?: seg else seg
            }
            ui = ui.copy(dayStart = dayStart, segments = segments, path = points.filter { it.accuracy <= 200 }.map { it.latLon }, loading = false, month = null)
            resolveNames(segments)
            suggestHomeWork()
        }
    }

    fun previousDay() = load(previousDay(ui.dayStart))

    fun nextDay() {
        if (!isToday) load(nextDay(ui.dayStart))
    }

    fun deleteDay() {
        val start = ui.dayStart
        scope.launch(Dispatchers.IO) {
            db.deleteRange(start, nextDay(start))
            withContext(Dispatchers.Main) { load(start) }
        }
    }

    fun deleteAll() {
        scope.launch(Dispatchers.IO) {
            db.deleteAll()
            withContext(Dispatchers.Main) { load() }
        }
    }

    /** The day's raw samples as a GPX track — what photo tools use to geotag pictures by time. */
    suspend fun exportDay(): File? = withContext(Dispatchers.IO) {
        val points = db.range(ui.dayStart, nextDay(ui.dayStart))
        if (points.size < 2) return@withContext null
        val title = "Timeline ${dayFile.format(Date(ui.dayStart))}"
        shareableGpx(context, title, Gpx.write(title, listOf(GpxTrack(title, null, points.map { GpxPoint(it.lat, it.lon, null, it.time) }))))
    }

    // ---- edits ---------------------------------------------------------------------------

    /** Names this place for every visit to it, past and future. */
    fun renameVisit(index: Int, name: String) {
        val v = ui.segments.getOrNull(index) as? Visit ?: return
        if (name.isBlank()) return
        scope.launch(Dispatchers.IO) { db.setCustomName(key(v.center), name.trim()) }
        ui = ui.copy(names = ui.names + (index to name.trim()))
    }

    fun setTripMode(index: Int, mode: TripMode) {
        val t = ui.segments.getOrNull(index) as? Trip ?: return
        scope.launch(Dispatchers.IO) { db.setTripMode(t.start, mode.name) }
        ui = ui.copy(segments = ui.segments.mapIndexed { i, s -> if (i == index) t.copy(mode = mode) else s })
    }

    /** Removes the samples behind one visit or trip, e.g. somewhere you'd rather not keep. */
    fun deleteSegment(index: Int) {
        val seg = ui.segments.getOrNull(index) ?: return
        val day = ui.dayStart
        scope.launch(Dispatchers.IO) {
            db.deleteRange(seg.start, seg.end + 1)
            withContext(Dispatchers.Main) { load(day) }
        }
    }

    // ---- month ---------------------------------------------------------------------------

    fun showMonth() {
        val cal = Calendar.getInstance().apply { timeInMillis = ui.dayStart; set(Calendar.DAY_OF_MONTH, 1) }
        val first = startOfDay(cal.timeInMillis)
        ui = ui.copy(loading = true)
        loadJob?.cancel()
        loadJob = scope.launch {
            val days = mutableListOf<List<Segment>>()
            var d = first
            val end = Calendar.getInstance().apply { timeInMillis = first; add(Calendar.MONTH, 1) }.timeInMillis
            while (d < end) {
                val pts = withContext(Dispatchers.IO) { db.range(d, nextDay(d)) }
                days += withContext(Dispatchers.Default) { TimelineBuilder.segments(pts) }
                d = nextDay(d)
            }
            val summary = TimelineInsights.month(days)
            ui = ui.copy(loading = false, month = summary)
            val names = summary.topPlaces.mapIndexedNotNull { i, (p, _) -> placeName(p)?.let { i to it } }.toMap()
            ui = ui.copy(monthNames = names)
        }
    }

    fun showDay() = load(ui.dayStart)

    fun dismissSuggestions() {
        ui = ui.copy(suggestedHome = null, suggestedWork = null)
    }

    /** Looks over the last 30 days for where nights and weekday daytimes are spent. */
    private var suggestionsChecked = false

    private suspend fun suggestHomeWork() {
        val (homeSet, workSet) = shortcutsSet()
        // Thirty days of history is too much to re-read on every day change; once a session is enough.
        if ((homeSet && workSet) || suggestionsChecked) return
        suggestionsChecked = true
        val now = System.currentTimeMillis()
        val (home, work) = withContext(Dispatchers.Default) {
            val pts = withContext(Dispatchers.IO) { db.range(now - 30L * 24 * 3_600_000, now) }
            val visits = TimelineBuilder.segments(pts).filterIsInstance<Visit>()
            val cal = Calendar.getInstance()
            TimelineInsights.homeAndWork(
                visits,
                hourOf = { cal.timeInMillis = it; cal.get(Calendar.HOUR_OF_DAY) },
                isWeekday = { cal.timeInMillis = it; cal.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY) },
            )
        }
        ui = ui.copy(suggestedHome = home.takeIf { !homeSet }, suggestedWork = work.takeIf { !workSet })
    }

    private fun key(p: LatLon) = "%.3f,%.3f".format(Locale.US, p.lat, p.lon)

    private suspend fun placeName(p: LatLon): String? {
        val near = savedPlaces().minByOrNull { distanceMeters(it.point, p) }?.takeIf { distanceMeters(it.point, p) < 100 }
        return near?.name ?: withContext(Dispatchers.IO) { db.customName(key(p)) ?: db.name(key(p)) }
    }

    /** Saved places first, then the cache, then a reverse lookup that's remembered. */
    private suspend fun resolveNames(segments: List<Segment>) {
        val saved = savedPlaces()
        segments.forEachIndexed { i, s ->
            if (s !is Visit) return@forEachIndexed
            val near = saved.minByOrNull { distanceMeters(it.point, s.center) }?.takeIf { distanceMeters(it.point, s.center) < 100 }
            val key = key(s.center)
            val name = withContext(Dispatchers.IO) { db.customName(key) }
                ?: near?.name
                ?: withContext(Dispatchers.IO) { db.name(key) }
                ?: runCatching { Geocoder.reverse(s.center)?.name }.getOrNull()?.also { n -> withContext(Dispatchers.IO) { db.putName(key, n) } }
                ?: return@forEachIndexed
            ui = ui.copy(names = ui.names + (i to name))
        }
    }

    companion object {
        private val dayFile = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun nextDay(dayStart: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        fun previousDay(dayStart: Long): Long = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    }
}
