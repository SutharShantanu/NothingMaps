package com.shantanu.nmaps.offline.engine

import java.io.ByteArrayOutputStream

/** The real z14 tiles around Sector 53, Gurgaon, extracted from a MapLibre offline database. */
object Fixtures {
    val ids = listOf(TileId(14, 11700, 6840), TileId(14, 11700, 6841), TileId(14, 11701, 6840), TileId(14, 11701, 6841))

    fun bytes(id: TileId): ByteArray =
        Fixtures::class.java.getResourceAsStream("/tiles/${id.z}-${id.x}-${id.y}.mvt")!!.use { it.readBytes() }

    val source by lazy { MemoryTileSource(ids.associateWith(::bytes)) }
}

/** Builds small vector tiles for synthetic tests. */
class MvtWriter {
    class Feature(val type: GeomType, val tags: Map<String, Any>, val parts: List<IntArray>, val id: Long = 0)

    private val layers = ByteArrayOutputStream()

    fun layer(name: String, features: List<Feature>, extent: Int = 4096): MvtWriter {
        val keys = features.flatMap { it.tags.keys }.distinct()
        val values = features.flatMap { it.tags.values }.distinct()
        val l = Buf()
        l.string(1, name)
        for (f in features) {
            val fb = Buf()
            if (f.id != 0L) fb.varintField(1, f.id)
            fb.packed(2, f.tags.flatMap { (k, v) -> listOf(keys.indexOf(k), values.indexOf(v)) })
            fb.varintField(3, f.type.ordinal.toLong())
            fb.packed(4, geometry(f))
            l.bytes(2, fb.toByteArray())
        }
        keys.forEach { l.string(3, it) }
        for (v in values) {
            val vb = Buf()
            when (v) {
                is String -> vb.string(1, v)
                is Float -> { vb.tag(2, 5); vb.fixed(v.toRawBits().toLong(), 4) }
                is Double -> { vb.tag(3, 1); vb.fixed(v.toRawBits(), 8) }
                is Int -> vb.varintField(4, v.toLong())
                is Long -> if (v >= 0) vb.varintField(5, v) else vb.varintField(6, (v shl 1) xor (v shr 63))
                is Boolean -> vb.varintField(7, if (v) 1 else 0)
            }
            l.bytes(4, vb.toByteArray())
        }
        l.varintField(5, extent.toLong())
        l.varintField(15, 2)
        Buf().apply { bytes(3, l.toByteArray()) }.toByteArray().let(layers::write)
        return this
    }

    fun build(): ByteArray = layers.toByteArray()

    private fun geometry(f: Feature): List<Int> {
        val out = ArrayList<Int>()
        var cx = 0
        var cy = 0
        fun zz(n: Int) = (n shl 1) xor (n shr 31)
        for (part in f.parts) {
            val n = part.size / 2
            if (f.type == GeomType.Point) {
                out += (n shl 3) or 1
                for (i in 0 until n) { out += zz(part[2 * i] - cx); out += zz(part[2 * i + 1] - cy); cx = part[2 * i]; cy = part[2 * i + 1] }
                continue
            }
            out += (1 shl 3) or 1
            out += zz(part[0] - cx); out += zz(part[1] - cy); cx = part[0]; cy = part[1]
            out += ((n - 1) shl 3) or 2
            for (i in 1 until n) { out += zz(part[2 * i] - cx); out += zz(part[2 * i + 1] - cy); cx = part[2 * i]; cy = part[2 * i + 1] }
            if (f.type == GeomType.Polygon) out += (1 shl 3) or 7
        }
        return out
    }

    private class Buf : ByteArrayOutputStream() {
        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            write(x.toInt())
        }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun varintField(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun fixed(v: Long, n: Int) { for (i in 0 until n) write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun packed(field: Int, vs: List<Int>) {
            val b = Buf()
            vs.forEach { b.varint(it.toLong() and 0xffffffffL) }
            bytes(field, b.toByteArray())
        }
    }
}

fun line(tags: Map<String, Any>, vararg xy: Int) = MvtWriter.Feature(GeomType.Line, tags, listOf(xy))
