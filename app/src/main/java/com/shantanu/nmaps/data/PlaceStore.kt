package com.shantanu.nmaps.data

import android.content.Context
import org.json.JSONArray

enum class Shortcut(val label: String, val key: String) { Home("Home", "home"), Work("Work", "work") }

/** Saved places and recent searches, kept on the device. */
class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun saved(): List<Place> = read(KEY_SAVED)
    fun recents(): List<Place> = read(KEY_RECENTS)

    fun toggleSaved(place: Place): List<Place> {
        val current = saved()
        val next = if (current.any { it.key == place.key }) current.filterNot { it.key == place.key } else listOf(place) + current
        write(KEY_SAVED, next)
        return next
    }

    fun addRecent(place: Place): List<Place> {
        val next = (listOf(place) + recents().filterNot { it.key == place.key }).take(MAX_RECENTS)
        write(KEY_RECENTS, next)
        return next
    }

    fun clearRecents(): List<Place> {
        write(KEY_RECENTS, emptyList())
        return emptyList()
    }

    /** Home or Work, the two one-tap shortcuts under the search bar. */
    fun shortcut(kind: Shortcut): Place? = read(kind.key).firstOrNull()

    fun setShortcut(kind: Shortcut, place: Place) = write(kind.key, listOf(place))

    fun parking(): Place? = read(KEY_PARKING).firstOrNull()

    fun setParking(place: Place?) = write(KEY_PARKING, listOfNotNull(place))

    private fun read(key: String): List<Place> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { Place.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(key: String, places: List<Place>) {
        prefs.edit().putString(key, JSONArray(places.map { it.toJson() }).toString()).apply()
    }

    private companion object {
        const val KEY_SAVED = "saved"
        const val KEY_RECENTS = "recents"
        const val KEY_PARKING = "parking"
        const val MAX_RECENTS = 8
    }
}
