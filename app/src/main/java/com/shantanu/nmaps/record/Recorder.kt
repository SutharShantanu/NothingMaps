package com.shantanu.nmaps.record

import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.nav.distanceMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Sport(val label: String, val showsPace: Boolean, val gpxType: String, val pauseSpeed: Float) {
    Run("Run", true, "running", 0.6f),
    Ride("Ride", false, "cycling", 1.2f),
    Walk("Walk", true, "walking", 0.4f),
}

enum class RecStatus { Idle, Recording, Paused, AutoPaused }

data class TrackPoint(val lat: Double, val lon: Double, val ele: Double?, val time: Long)

data class RecordState(
    val status: RecStatus = RecStatus.Idle,
    val sport: Sport = Sport.Run,
    val startedAt: Long = 0,
    /** Time spent moving; auto-pauses and manual pauses don't count, like Strava's moving time. */
    val movingMs: Long = 0,
    val distance: Double = 0.0,
    val elevationGain: Double = 0.0,
    val altitude: Double? = null,
    val usingBarometer: Boolean = false,
    val steps: Int = 0,
    val cadence: Int = 0,
    val speed: Float = 0f,
    val satellites: Int = 0,
    val accuracy: Float? = null,
    val track: List<LatLon> = emptyList(),
)

data class RecordedActivity(
    val sport: Sport,
    val startedAt: Long,
    val movingMs: Long,
    val distance: Double,
    val elevationGain: Double,
    val steps: Int,
    val points: List<TrackPoint>,
)

/**
 * Turns raw fixes and sensor readings into an activity. Lives for the whole process so the
 * foreground service can feed it while the UI comes and goes.
 */
object Recorder {
    private val _state = MutableStateFlow(RecordState())
    val state: StateFlow<RecordState> = _state.asStateFlow()

    private val points = ArrayList<TrackPoint>()
    private var lastCounted: TrackPoint? = null
    private var lastTick = 0L
    private var slowSince = 0L
    private var pausedAt: LatLon? = null
    private var still = false
    private var trackDirty = false

    private var stepBase = -1f
    private val stepWindow = ArrayDeque<Pair<Long, Float>>()

    // Elevation: smoothed altitude and the reference it must climb past to count as gain.
    private var eleSmoothed: Double? = null
    private var eleRef: Double? = null
    private var baroAltitude: Double? = null
    private var baroOffset: Double? = null

    val isActive get() = _state.value.status != RecStatus.Idle

    @Synchronized
    fun start(sport: Sport, now: Long = System.currentTimeMillis()) {
        reset()
        lastTick = now
        _state.value = RecordState(status = RecStatus.Recording, sport = sport, startedAt = now)
    }

    @Synchronized
    fun pause() {
        _state.update { if (it.status == RecStatus.Idle) it else it.copy(status = RecStatus.Paused) }
    }

    @Synchronized
    fun resume() {
        if (_state.value.status != RecStatus.Paused) return
        // Don't count the gap walked while paused.
        lastCounted = null
        slowSince = 0
        _state.update { it.copy(status = RecStatus.Recording) }
    }

    @Synchronized
    fun finish(): RecordedActivity? {
        val s = _state.value
        if (s.status == RecStatus.Idle) return null
        val activity = RecordedActivity(s.sport, s.startedAt, s.movingMs, s.distance, s.elevationGain, s.steps, points.toList())
        reset()
        _state.value = RecordState()
        return activity
    }

    @Synchronized
    fun tick(now: Long) {
        val dt = now - lastTick
        lastTick = now
        _state.update { s ->
            var next = s
            if (s.status == RecStatus.Recording) next = next.copy(movingMs = s.movingMs + dt)
            if (trackDirty && (now / 1000) % 3 == 0L) {
                trackDirty = false
                next = next.copy(track = points.map { LatLon(it.lat, it.lon) })
            }
            next
        }
    }

    @Synchronized
    fun onFix(fix: Fix) {
        val s = _state.value
        if (s.status == RecStatus.Idle) return
        // A cached fix from before the start (e.g. last known location) isn't where the activity began.
        if (fix.time < s.startedAt - 2_000) return
        val now = fix.time
        _state.update { it.copy(accuracy = fix.accuracy, speed = it.speed + (fix.speed - it.speed) * 0.4f) }

        // Barometer drifts with the weather; anchor it to GPS altitude slowly.
        if (fix.altitude != null && fix.accuracy < 20) {
            val baro = baroAltitude
            if (baro != null) {
                val target = fix.altitude - baro
                baroOffset = baroOffset?.let { it + (target - it) * 0.02 } ?: target
            } else {
                elevation(fix.altitude, alpha = 0.3, threshold = 5.0)
            }
        }

        if (fix.accuracy > 30 || s.status == RecStatus.Paused) return
        val p = TrackPoint(fix.lat, fix.lon, eleSmoothed, now)

        when (s.status) {
            RecStatus.Recording -> {
                if (fix.speed < s.sport.pauseSpeed) {
                    if (slowSince == 0L) slowSince = now
                    else if (now - slowSince > 5_000) autoPause(p)
                } else {
                    slowSince = 0
                }
            }
            RecStatus.AutoPaused -> {
                val moved = pausedAt?.let { distanceMeters(it, LatLon(p.lat, p.lon)) } ?: 0.0
                if (!still && (fix.speed > s.sport.pauseSpeed * 1.5f || moved > fix.accuracy + 8)) {
                    slowSince = 0
                    _state.update { it.copy(status = RecStatus.Recording) }
                }
            }
            else -> Unit
        }

        if (_state.value.status != RecStatus.Recording) return
        val last = lastCounted
        if (last == null) {
            add(p)
        } else {
            val d = distanceMeters(LatLon(last.lat, last.lon), LatLon(p.lat, p.lon))
            // Ignore wander smaller than the fix's own uncertainty.
            if (d >= maxOf(3.0, fix.accuracy * 0.5)) {
                _state.update { it.copy(distance = it.distance + d) }
                add(p)
            }
        }
    }

    @Synchronized
    fun onStill(isStill: Boolean) {
        still = isStill
        // The accelerometer notices a stop sooner than GPS speed does.
        if (isStill && _state.value.status == RecStatus.Recording) {
            autoPause(points.lastOrNull())
        }
    }

    @Synchronized
    fun onSatellites(count: Int) {
        _state.update { it.copy(satellites = count) }
    }

    @Synchronized
    fun onSteps(total: Float, now: Long = System.currentTimeMillis()) {
        if (_state.value.status == RecStatus.Idle) return
        if (stepBase < 0) stepBase = total
        stepWindow.addLast(now to total)
        while (stepWindow.isNotEmpty() && now - stepWindow.first().first > 60_000) stepWindow.removeFirst()
        val span = (stepWindow.last().first - stepWindow.first().first) / 1000.0
        val cadence = if (span >= 10) ((stepWindow.last().second - stepWindow.first().second) / span * 60).toInt() else 0
        _state.update { it.copy(steps = (total - stepBase).toInt(), cadence = cadence) }
    }

    @Synchronized
    fun onBarometer(altitude: Float) {
        if (_state.value.status == RecStatus.Idle) return
        baroAltitude = altitude.toDouble()
        val anchored = altitude + (baroOffset ?: 0.0)
        _state.update { it.copy(usingBarometer = true) }
        elevation(anchored, alpha = 0.1, threshold = 2.0)
    }

    private fun elevation(alt: Double, alpha: Double, threshold: Double) {
        val smoothed = eleSmoothed?.let { it + (alt - it) * alpha } ?: alt
        eleSmoothed = smoothed
        val ref = eleRef ?: smoothed.also { eleRef = it }
        when {
            smoothed - ref >= threshold -> {
                if (_state.value.status == RecStatus.Recording) _state.update { it.copy(elevationGain = it.elevationGain + (smoothed - ref)) }
                eleRef = smoothed
            }
            ref - smoothed >= threshold -> eleRef = smoothed
        }
        _state.update { it.copy(altitude = smoothed) }
    }

    private fun autoPause(at: TrackPoint?) {
        pausedAt = at?.let { LatLon(it.lat, it.lon) }
        _state.update { it.copy(status = RecStatus.AutoPaused) }
    }

    private fun add(p: TrackPoint) {
        points += p
        lastCounted = p
        trackDirty = true
    }

    private fun reset() {
        points.clear()
        lastCounted = null
        slowSince = 0
        pausedAt = null
        still = false
        trackDirty = false
        stepBase = -1f
        stepWindow.clear()
        eleSmoothed = null
        eleRef = null
        baroAltitude = null
        baroOffset = null
    }
}
