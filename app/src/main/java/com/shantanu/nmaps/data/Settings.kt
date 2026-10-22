package com.shantanu.nmaps.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class BaseMap(val label: String) { Default("Default"), Satellite("Satellite") }

/** What the map draws: one base map plus optional overlays, like Google Maps' layer sheet. */
data class MapLayers(
    val base: BaseMap = BaseMap.Default,
    val terrain: Boolean = false,
    val transit: Boolean = false,
    val cycling: Boolean = false,
    val buildings3d: Boolean = false,
    /** Geotagged pictures from the gallery, as thumbnails where they were taken. */
    val photos: Boolean = false,
)

data class AppSettings(
    val imperial: Boolean = false,
    val voice: Boolean = true,
    val autoNight: Boolean = true,
    val timeline: Boolean = false,
    /** Steps and workouts from Health Connect shown in the timeline. */
    val health: Boolean = false,
    /** Name shown to people you share your live location with. */
    val shareName: String = "",
    val layers: MapLayers = MapLayers(),
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    // Map controls
    val zoomButtons: Boolean = false,
    val scaleBar: Boolean = true,
    val coordinates: Boolean = false,
    val rotate: Boolean = true,
)

/** Unit choice read by the formatters; set from settings at start-up and when changed. */
object UnitPrefs {
    @Volatile var imperial = false
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        imperial = prefs.getBoolean("imperial", false),
        voice = prefs.getBoolean("voice", true),
        autoNight = prefs.getBoolean("autoNight", true),
        timeline = prefs.getBoolean("timeline", false),
        health = prefs.getBoolean("health", false),
        shareName = prefs.getString("shareName", "").orEmpty(),
        avoidTolls = prefs.getBoolean("avoidTolls", false),
        avoidHighways = prefs.getBoolean("avoidHighways", false),
        avoidFerries = prefs.getBoolean("avoidFerries", false),
        zoomButtons = prefs.getBoolean("zoomButtons", false),
        scaleBar = prefs.getBoolean("scaleBar", true),
        coordinates = prefs.getBoolean("coordinates", false),
        rotate = prefs.getBoolean("rotate", true),
        layers = MapLayers(
            base = runCatching { BaseMap.valueOf(prefs.getString("base", "Default")!!) }.getOrDefault(BaseMap.Default),
            terrain = prefs.getBoolean("terrain", false),
            transit = prefs.getBoolean("transit", false),
            cycling = prefs.getBoolean("cycling", false),
            buildings3d = prefs.getBoolean("buildings3d", false),
            photos = prefs.getBoolean("photos", false),
        ),
    ).also { UnitPrefs.imperial = it.imperial }

    fun save(s: AppSettings) {
        UnitPrefs.imperial = s.imperial
        prefs.edit()
            .putBoolean("imperial", s.imperial)
            .putBoolean("voice", s.voice)
            .putBoolean("autoNight", s.autoNight)
            .putBoolean("timeline", s.timeline)
            .putBoolean("health", s.health)
            .putString("shareName", s.shareName)
            .putBoolean("avoidTolls", s.avoidTolls)
            .putBoolean("avoidHighways", s.avoidHighways)
            .putBoolean("avoidFerries", s.avoidFerries)
            .putBoolean("zoomButtons", s.zoomButtons)
            .putBoolean("scaleBar", s.scaleBar)
            .putBoolean("coordinates", s.coordinates)
            .putBoolean("rotate", s.rotate)
            .putString("base", s.layers.base.name)
            .putBoolean("terrain", s.layers.terrain)
            .putBoolean("transit", s.layers.transit)
            .putBoolean("cycling", s.layers.cycling)
            .putBoolean("buildings3d", s.layers.buildings3d)
            .putBoolean("photos", s.layers.photos)
            .apply()
    }
}

/** True while the device has a validated internet connection. */
fun onlineFlow(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    fun current() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    trySend(current())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            trySend(current())
        }
    }
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
