package com.shantanu.nmaps.timeline

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import com.shantanu.nmaps.data.SettingsStore

/** Location history, kept only on this phone. */
class HistoryDb private constructor(context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points (t INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL NOT NULL, spd REAL NOT NULL)")
        db.execSQL("CREATE TABLE names (k TEXT PRIMARY KEY, name TEXT NOT NULL)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: the user's corrections. Named places win over looked-up names; trip modes are keyed by start time.
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS custom_names (k TEXT PRIMARY KEY, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS trip_modes (start INTEGER PRIMARY KEY, mode TEXT NOT NULL)")
        }
    }

    private var lastStored: HistoryPoint? = null

    /**
     * Stores a sample. Dense sources (navigation, recording) are thinned to one sample every
     * 15 s or 30 m so a day stays small; sparse background samples always go in.
     */
    @Synchronized
    fun add(p: HistoryPoint, thin: Boolean) {
        val last = lastStored
        if (thin && last != null && p.time - last.time < 15_000 &&
            com.shantanu.nmaps.nav.distanceMeters(last.latLon, p.latLon) < 30
        ) return
        lastStored = p
        writableDatabase.insertWithOnConflict(
            "points", null,
            ContentValues().apply {
                put("t", p.time); put("lat", p.lat); put("lon", p.lon); put("acc", p.accuracy.toDouble()); put("spd", p.speed.toDouble())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun range(from: Long, to: Long): List<HistoryPoint> =
        readableDatabase.rawQuery("SELECT t, lat, lon, acc, spd FROM points WHERE t >= ? AND t < ? ORDER BY t", arrayOf("$from", "$to")).use { c ->
            buildList { while (c.moveToNext()) add(HistoryPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getFloat(3), c.getFloat(4))) }
        }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM points", null).use { it.moveToFirst(); it.getLong(0) }

    fun deleteRange(from: Long, to: Long) {
        writableDatabase.delete("points", "t >= ? AND t < ?", arrayOf("$from", "$to"))
    }

    fun deleteAll() {
        writableDatabase.delete("points", null, null)
        writableDatabase.delete("names", null, null)
        writableDatabase.delete("custom_names", null, null)
        writableDatabase.delete("trip_modes", null, null)
        lastStored = null
    }

    /** A name the user gave a place; applies to every visit there. */
    fun customName(key: String): String? =
        readableDatabase.rawQuery("SELECT name FROM custom_names WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setCustomName(key: String, name: String) {
        writableDatabase.insertWithOnConflict("custom_names", null, ContentValues().apply { put("k", key); put("name", name) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tripModes(from: Long, to: Long): Map<Long, String> =
        readableDatabase.rawQuery("SELECT start, mode FROM trip_modes WHERE start >= ? AND start < ?", arrayOf("$from", "$to")).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }

    fun setTripMode(start: Long, mode: String) {
        writableDatabase.insertWithOnConflict("trip_modes", null, ContentValues().apply { put("start", start); put("mode", mode) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Cached place names for visits, keyed by position rounded to ~100 m. */
    fun name(key: String): String? =
        readableDatabase.rawQuery("SELECT name FROM names WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putName(key: String, name: String) {
        writableDatabase.insertWithOnConflict("names", null, ContentValues().apply { put("k", key); put("name", name) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: HistoryDb(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Background collection for the timeline: a low-power fused request plus the passive provider
 * (free fixes whenever any other app asks for location), delivered to [TimelineReceiver].
 */
object TimelineCollector {
    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 41, Intent(context, TimelineReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java)
        val pi = intent(context)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                val request = LocationRequest.Builder(3 * 60_000L)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateDistanceMeters(40f)
                    .setMaxUpdateDelayMillis(15 * 60_000L)
                    .build()
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, pi)
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3 * 60_000L, 40f, pi)
            }
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000L, 20f, pi)
        }.isSuccess
    }

    fun stop(context: Context) {
        context.getSystemService(LocationManager::class.java).removeUpdates(intent(context))
    }
}

class TimelineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SettingsStore(context).load().timeline) return
        val db = HistoryDb.get(context)
        @Suppress("DEPRECATION")
        val batch: List<Location> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                intent.getParcelableArrayListExtra<Location>(LocationManager.KEY_LOCATIONS)?.let { addAll(it) }
            }
            if (isEmpty()) intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)?.let { add(it) }
        }
        batch.forEach { db.add(HistoryPoint(it.time, it.latitude, it.longitude, it.accuracy, if (it.hasSpeed()) it.speed else 0f), thin = false) }
    }
}

/** Background requests don't survive a reboot; re-register them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && SettingsStore(context).load().timeline) {
            TimelineCollector.start(context)
        }
    }
}
