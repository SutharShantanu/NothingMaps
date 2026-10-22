package com.shantanu.nmaps.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

data class HealthSession(val start: Long, val end: Long, val label: String)

/** A day's activity as the phone's health store has it: steps, and workouts from any app that writes there. */
data class HealthDay(val steps: Long, val sessions: List<HealthSession>)

/**
 * Health Connect is where Google Fit, Samsung Health, Strava and the rest put their data now that the
 * Fit APIs are gone, so it's the one place to read activity from.
 */
object HealthConnect {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun available(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun requestContract() = PermissionController.createRequestPermissionResultContract()

    /** Forgets the grant on the Health Connect side too, so "off" really disconnects. */
    suspend fun disconnect(context: Context) {
        if (!available(context)) return
        runCatching { HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions() }
    }

    /** Health Connect's own page for this app: see and change what it may read. */
    fun openSettings(context: Context) {
        runCatching { context.startActivity(android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
    }

    suspend fun granted(context: Context): Boolean =
        available(context) && HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)

    /** Steps and workouts between [start] and [end]; null when Health Connect isn't there or isn't allowed. */
    suspend fun day(context: Context, start: Long, end: Long): HealthDay? {
        if (!granted(context)) return null
        val client = HealthConnectClient.getOrCreate(context)
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
        val steps = runCatching { client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL] ?: 0L }.getOrDefault(0L)
        val sessions = runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records.map {
                HealthSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.title?.takeIf { t -> t.isNotBlank() } ?: label(it.exerciseType))
            }
        }.getOrDefault(emptyList())
        return HealthDay(steps, sessions.sortedBy { it.start })
    }

    private fun label(type: Int) = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Ride"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
        else -> "Workout"
    }
}
