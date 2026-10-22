package com.shantanu.nmaps.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Location fixes and satellite status from the platform LocationManager — no Play Services dependency. */
class LocationTracker(context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val mainExecutor = context.mainExecutor

    /** Callers must hold a location permission before collecting. */
    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        var last: Location? = null
        val listener = LocationListener { loc ->
            // With GPS and network both on, drop a coarse network fix that would yank the puck away from a recent precise one.
            val prev = last
            if (prev != null && loc.accuracy > prev.accuracy * 3 && loc.time - prev.time < 10_000) return@LocationListener
            last = loc
            trySend(loc.toFix())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.let { trySend(it.toFix()) }
            // High-accuracy fused requests use GNSS plus Wi-Fi/cell, once a second — what navigation and recording need.
            val request = LocationRequest.Builder(1000L)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(500L)
                .build()
            manager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, mainExecutor, listener)
        } else {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
            providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { trySend(it.toFix()) }
            providers.forEach { manager.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) }
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    /** Number of satellites used in the current fix. */
    @SuppressLint("MissingPermission")
    fun satellites(): Flow<Int> = callbackFlow {
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                trySend((0 until status.satelliteCount).count { status.usedInFix(it) })
            }

            override fun onStopped() {
                trySend(0)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.registerGnssStatusCallback(mainExecutor, callback)
        } else {
            manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        }
        awaitClose { manager.unregisterGnssStatusCallback(callback) }
    }

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        accuracy = accuracy,
        speed = if (hasSpeed()) speed else 0f,
        bearing = if (hasBearing() && hasSpeed() && speed > 1f) bearing else null,
        altitude = if (hasAltitude()) altitude else null,
        time = time,
    )
}
