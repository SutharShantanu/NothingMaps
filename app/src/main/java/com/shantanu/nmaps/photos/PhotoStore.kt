package com.shantanu.nmaps.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.shantanu.nmaps.data.LatLon
import com.shantanu.nmaps.offline.Bounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A picture in the gallery that knows where it was taken. */
data class GeoPhoto(val id: Long, val lat: Double, val lon: Double, val time: Long) {
    val point get() = LatLon(lat, lon)
    val uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

/**
 * Geotagged photos from the device gallery. Reading GPS from EXIF needs the original file, so the
 * result is cached per photo in a small JSON file; a scan only opens pictures it hasn't seen.
 */
class PhotoStore(private val context: Context) {
    private val file = File(context.filesDir, "photos.json")
    private val thumbs = LruCache<Long, Bitmap>(200)

    // Seeded from the cache here: the view model is built during composition, where state can't be written.
    var photos by mutableStateOf(readCache().values.filterNotNull())
        private set

    /** The permissions the gallery scan needs on this Android version. */
    val permissions: List<String> = buildList {
        add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    fun hasPermission(): Boolean {
        val images = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 may grant a user-chosen subset of photos; that's enough to show.
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, permissions.first()) == PackageManager.PERMISSION_GRANTED
        }
        return images && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    /** Looks at what's in the gallery now, reads location from pictures not seen before, and keeps the result. */
    suspend fun scan() = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext
        val current = linkedMapOf<Long, Long>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext()) current[c.getLong(0)] = c.getLong(1)
        }
        val cached = readCache()
        val (kept, fresh) = merge(cached, current.keys.toList())
        val scanned = HashMap<Long, GeoPhoto?>(kept)
        for (id in fresh) scanned[id] = readLocation(id, current[id] ?: 0L)
        writeCache(scanned)
        photos = scanned.values.filterNotNull().sortedByDescending { it.time }
    }

    private fun readLocation(id: Long, time: Long): GeoPhoto? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(original)?.use { ExifInterface(it).latLong }
    }.getOrNull()?.takeIf { it[0] != 0.0 || it[1] != 0.0 }?.let { GeoPhoto(id, it[0], it[1], time) }

    /** Photos inside [bounds], newest first, at most [max]. */
    fun inBounds(bounds: Bounds, max: Int = 80): List<GeoPhoto> =
        photos.asSequence().filter { it.lat in bounds.south..bounds.north && it.lon in bounds.west..bounds.east }.take(max).toList()

    /** A round thumbnail with a white ring, sized for the map. */
    suspend fun thumbnail(photo: GeoPhoto, sizePx: Int): Bitmap? = thumbs.get(photo.id) ?: withContext(Dispatchers.IO) {
        runCatching {
            val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.loadThumbnail(photo.uri, Size(sizePx, sizePx), null)
            else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, photo.id, MediaStore.Images.Thumbnails.MICRO_KIND, null)
            round(src, sizePx)
        }.getOrNull()?.also { thumbs.put(photo.id, it) }
    }

    private fun round(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size / 2f
        val ring = size * 0.06f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val scale = (size - ring * 2) / minOf(src.width, src.height)
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(r - src.width * scale / 2, r - src.height * scale / 2)
                })
            }
        }
        c.drawCircle(r, r, r - ring, paint)
        return out
    }

    // Cache rows: [id, lat, lon, time]; lat/lon null for pictures without a location, so they're not re-read.
    private fun readCache(): Map<Long, GeoPhoto?> = runCatching {
        val arr = JSONArray(file.readText())
        buildMap {
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                put(row.getLong(0), if (row.isNull(1)) null else GeoPhoto(row.getLong(0), row.getDouble(1), row.getDouble(2), row.getLong(3)))
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(map: Map<Long, GeoPhoto?>) {
        val arr = JSONArray()
        map.forEach { (id, p) ->
            arr.put(JSONArray().put(id).put(p?.lat ?: JSONObject.NULL).put(p?.lon ?: JSONObject.NULL).put(p?.time ?: 0L))
        }
        file.writeText(arr.toString())
    }

    companion object {
        /** What stays from the cache (pictures still in the gallery) and which ids still need their EXIF read. */
        fun merge(cached: Map<Long, GeoPhoto?>, current: List<Long>): Pair<Map<Long, GeoPhoto?>, List<Long>> {
            val kept = cached.filterKeys { it in current.toHashSet() }
            return kept to current.filterNot { it in cached }
        }
    }
}
