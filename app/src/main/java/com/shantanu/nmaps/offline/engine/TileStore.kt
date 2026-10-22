package com.shantanu.nmaps.offline.engine

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

data class TileId(val z: Int, val x: Int, val y: Int)

/** Vector tiles available offline, as decompressed MVT bytes. */
interface TileSource {
    fun tiles(z: Int): List<TileId>
    fun tile(id: TileId): ByteArray?
}

class MemoryTileSource(private val tiles: Map<TileId, ByteArray>) : TileSource {
    override fun tiles(z: Int) = tiles.keys.filter { it.z == z }
    override fun tile(id: TileId) = tiles[id]
}

/**
 * Reads the vector tiles MapLibre keeps in `mbgl-offline.db` (downloaded regions plus the ambient
 * cache). Tiles of a region win over cached ones, and the newest planet build wins over older ones.
 */
class OfflineDbTileSource(private val file: File) : TileSource {
    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun db(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrNull().also { db = it }
    }

    override fun tiles(z: Int): List<TileId> = runCatching {
        db()?.rawQuery("SELECT DISTINCT x, y FROM tiles WHERE z = ? AND url_template LIKE ?", arrayOf("$z", PLANET))?.use { c ->
            buildList { while (c.moveToNext()) add(TileId(z, c.getInt(0), c.getInt(1))) }
        }
    }.getOrNull().orEmpty()

    override fun tile(id: TileId): ByteArray? = runCatching {
        db()?.rawQuery(
            "SELECT t.data, t.compressed FROM tiles t LEFT JOIN region_tiles r ON r.tile_id = t.id " +
                "WHERE t.z = ? AND t.x = ? AND t.y = ? AND t.url_template LIKE ? AND t.data IS NOT NULL " +
                "ORDER BY r.tile_id IS NULL, t.url_template DESC LIMIT 1",
            arrayOf("${id.z}", "${id.x}", "${id.y}", PLANET),
        )?.use { c -> if (c.moveToFirst()) inflate(c.getBlob(0), c.getInt(1) != 0) else null }
    }.getOrNull()

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }

    private companion object {
        const val PLANET = "%tiles.openfreemap.org/planet/%"
    }
}

/** MapLibre stores tiles zlib-compressed when `compressed` is set; the server may also have gzipped them. */
internal fun inflate(data: ByteArray, compressed: Boolean): ByteArray {
    val raw = if (compressed) zlib(data) else data
    return if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
}

private fun zlib(data: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** Decoded tiles with a small LRU, shared by search and routing. */
class TileCache(val source: TileSource, private val capacity: Int = 96) {
    private val cache = object : LinkedHashMap<TileId, MvtTile>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, MvtTile>) = size > capacity
    }

    fun get(id: TileId): MvtTile? {
        synchronized(cache) { cache[id]?.let { return it } }
        val tile = source.tile(id)?.let { runCatching { MvtTile.decode(it) }.getOrNull() } ?: return null
        synchronized(cache) { cache[id] = tile }
        return tile
    }

    fun clear() = synchronized(cache) { cache.clear() }
}
