package com.shantanu.nmaps.offline.engine

import com.shantanu.nmaps.data.LatLon
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A decoded Mapbox Vector Tile (spec 2.1). Only what the offline engine needs, no dependencies.
 * Layers are decoded on first access, as search and routing each read only a few of them.
 */
class MvtTile private constructor(private val raw: Map<String, Proto>) {
    private val decoded = HashMap<String, MvtLayer>()

    val layers: List<MvtLayer> get() = raw.keys.mapNotNull(::layer)

    fun layer(name: String): MvtLayer? = synchronized(decoded) {
        decoded[name] ?: raw[name]?.let { runCatching { MvtLayer.decode(it.copy()) }.getOrNull() }?.also { decoded[name] = it }
    }

    companion object {
        fun decode(bytes: ByteArray): MvtTile {
            val r = Proto(bytes, 0, bytes.size)
            val layers = LinkedHashMap<String, Proto>()
            while (r.more()) {
                val key = r.varint().toInt()
                if (key ushr 3 == 3 && key and 7 == 2) {
                    val layer = r.sub()
                    layers.putIfAbsent(MvtLayer.name(layer.copy()), layer)
                } else {
                    r.skip(key and 7)
                }
            }
            return MvtTile(layers)
        }
    }
}

class MvtLayer(
    val name: String,
    val extent: Int,
    val keys: List<String>,
    val values: List<Any>,
    val features: List<MvtFeature>,
) {
    private val keyIndex = keys.withIndex().associate { (i, k) -> k to i }

    internal fun keyOf(key: String) = keyIndex[key] ?: -1

    internal companion object {
        fun name(r: Proto): String {
            while (r.more()) {
                val key = r.varint().toInt()
                if (key ushr 3 == 1 && key and 7 == 2) return r.string()
                r.skip(key and 7)
            }
            return ""
        }

        fun decode(r: Proto): MvtLayer {
            var name = ""
            var extent = 4096
            val keys = ArrayList<String>()
            val values = ArrayList<Any>()
            val raw = ArrayList<Proto>()
            while (r.more()) {
                val key = r.varint().toInt()
                when (key ushr 3) {
                    1 -> name = r.string()
                    2 -> raw += r.sub()
                    3 -> keys += r.string()
                    4 -> values += value(r.sub())
                    5 -> extent = r.varint().toInt()
                    else -> r.skip(key and 7)
                }
            }
            val features = ArrayList<MvtFeature>(raw.size)
            val layer = MvtLayer(name, extent, keys, values, features)
            raw.forEach { features += MvtFeature.decode(it, layer) }
            return layer
        }

        private fun value(r: Proto): Any {
            var v: Any = ""
            while (r.more()) {
                val key = r.varint().toInt()
                v = when (key ushr 3) {
                    1 -> r.string()
                    2 -> Float.fromBits(r.fixed32()).toDouble()
                    3 -> Double.fromBits(r.fixed64())
                    4, 5 -> r.varint()
                    6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                    7 -> r.varint() != 0L
                    else -> { r.skip(key and 7); v }
                }
            }
            return v
        }
    }
}

enum class GeomType { Unknown, Point, Line, Polygon }

/**
 * Tags and geometry are kept as the raw index/command arrays and only expanded when asked for,
 * since most features of a tile are never looked at.
 */
class MvtFeature internal constructor(
    val id: Long,
    val type: GeomType,
    private val tagIdx: IntArray,
    private val commands: IntArray,
    private val layer: MvtLayer,
) {
    val tags: Map<String, Any> by lazy {
        (0 until tagIdx.size / 2).mapNotNull { i ->
            val k = layer.keys.getOrNull(tagIdx[i * 2]) ?: return@mapNotNull null
            layer.values.getOrNull(tagIdx[i * 2 + 1])?.let { k to it }
        }.toMap()
    }

    /** Parts in tile coordinates as flat [x0, y0, x1, y1, …]: one per line or ring, or a single part holding all points. */
    val geometry: List<IntArray> by lazy { decodeGeometry(commands) }

    operator fun get(key: String): Any? {
        val k = layer.keyOf(key)
        if (k < 0) return null
        for (i in 0 until tagIdx.size - 1 step 2) if (tagIdx[i] == k) return layer.values.getOrNull(tagIdx[i + 1])
        return null
    }

    fun string(key: String) = get(key) as? String

    fun long(key: String) = when (val v = get(key)) {
        is Long -> v
        is Double -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.toLongOrNull()
        else -> null
    }

    internal companion object {
        fun decode(r: Proto, layer: MvtLayer): MvtFeature {
            var id = 0L
            var type = GeomType.Unknown
            var tags = IntArray(0)
            var geom = IntArray(0)
            while (r.more()) {
                val key = r.varint().toInt()
                when (key ushr 3) {
                    1 -> id = r.varint()
                    2 -> tags = r.packed()
                    3 -> type = GeomType.entries.getOrElse(r.varint().toInt()) { GeomType.Unknown }
                    4 -> geom = r.packed()
                    else -> r.skip(key and 7)
                }
            }
            return MvtFeature(id, type, tags, geom, layer)
        }

        fun decodeGeometry(cmd: IntArray): List<IntArray> {
            val parts = ArrayList<IntArray>()
            var cur = IntBuf()
            var x = 0
            var y = 0
            var i = 0
            while (i < cmd.size) {
                val c = cmd[i] and 7
                val count = cmd[i] ushr 3
                i++
                when (c) {
                    1, 2 -> {
                        if (c == 1 && cur.size > 0) {
                            parts += cur.toArray()
                            cur = IntBuf()
                        }
                        repeat(count) {
                            if (i + 1 >= cmd.size) return parts.also { if (cur.size > 0) it += cur.toArray() }
                            x += zigzag(cmd[i])
                            y += zigzag(cmd[i + 1])
                            i += 2
                            cur.add(x)
                            cur.add(y)
                        }
                    }
                    7 -> Unit // Rings are left open; the first vertex closes them.
                    else -> return parts.also { if (cur.size > 0) it += cur.toArray() }
                }
            }
            if (cur.size > 0) parts += cur.toArray()
            return parts
        }

        private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)
    }
}

/** World size in global pixels at z14 with 4096-unit tiles: tiles share vertices exactly on this grid. */
internal const val GLOBAL_ZOOM = 14
internal const val GLOBAL_EXTENT = 4096
internal const val WORLD = (1 shl GLOBAL_ZOOM).toDouble() * GLOBAL_EXTENT

/** Position of tile pixel ([px], [py]) of tile [z]/[x]/[y] with the given [extent]. */
fun tileToLatLon(z: Int, x: Int, y: Int, extent: Int, px: Double, py: Double): LatLon {
    val n = (1 shl z).toDouble()
    return mercatorToLatLon((x + px / extent) / n, (y + py / extent) / n)
}

/** Web-mercator fraction of the world (0..1, y down) to coordinates. */
internal fun mercatorToLatLon(fx: Double, fy: Double) =
    LatLon(Math.toDegrees(atan(sinh(PI * (1 - 2 * fy)))), fx * 360 - 180)

internal fun lonToFraction(lon: Double) = (lon + 180) / 360

internal fun latToFraction(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
}

/** Protobuf wire-format reader over a slice of a byte array. */
internal class Proto(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    fun more() = pos < end

    fun copy() = Proto(buf, pos, end)

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xff) shl (8 * i))
        pos += 4
        return v
    }

    fun fixed64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 8
        return v
    }

    fun sub(): Proto {
        val len = varint().toInt()
        val p = Proto(buf, pos, pos + len)
        pos += len
        return p
    }

    fun string(): String {
        val len = varint().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun packed(): IntArray {
        val p = sub()
        val out = IntBuf()
        while (p.more()) out.add(p.varint().toInt())
        return out.toArray()
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> throw IllegalArgumentException("Unsupported wire type $wireType")
        }
    }
}

internal class IntBuf(capacity: Int = 16) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]

    operator fun set(i: Int, v: Int) {
        data[i] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}
