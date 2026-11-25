package com.vitrinevideo.engine.graphics

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal pure-Kotlin TTF inspector used to prove that a declared font weight
 * is actually available in the packaged font file. Reads OS/2.usWeightClass,
 * name records 1/2/4/17 and the `fvar` weight axis range.
 */
object TrueTypeFontInspector {
    data class Info(
        val sfntVersion: Int,
        val weightClass: Int?,
        val familyName: String?,
        val subfamilyName: String?,
        val fullName: String?,
        val weightName: String?,
        val hasWeightAxis: Boolean,
        val weightAxisMin: Float?,
        val weightAxisMax: Float?
    ) {
        val canRenderDeclaredBlack: Boolean
            get() = hasWeightAxis && weightAxisMax != null && weightAxisMax!! >= 900f
    }

    fun inspect(bytes: ByteArray): Info {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12) return Info(0, null, null, null, null, null, false, null, null)
        val sfnt = bb.getInt(0)
        if (sfnt != 0x00010000 && sfnt.toInt() != 0x4F54544F) {
            return Info(sfnt, null, null, null, null, null, false, null, null)
        }
        val tableCount = bb.getUShort(4)
        val tables = HashMap<String, Int>()
        for (i in 0 until tableCount) {
            val off = 12 + i * 16
            if (off + 16 > bytes.size) break
            val tag = String(bytes, off, 4, Charsets.US_ASCII)
            val start = bb.getInt(off + 8)
            if (start >= 0 && start < bytes.size) tables[tag] = start
        }
        val os2 = tables["OS/2"]?.let { off ->
            if (off + 6 <= bytes.size) bb.getUShort(off + 4).toInt() else null
        }
        val names = tables["name"]?.let { parseNames(bb, it, bytes.size) } ?: emptyMap()
        val fvar = tables["fvar"]?.let { parseFvar(bb, it, bytes.size, tables["STAT"]) }
        return Info(
            sfntVersion = sfnt,
            weightClass = os2,
            familyName = names[1],
            subfamilyName = names[2],
            fullName = names[4],
            weightName = names[17],
            hasWeightAxis = fvar?.first == true,
            weightAxisMin = fvar?.second,
            weightAxisMax = fvar?.third
        )
    }

    private fun parseNames(bb: ByteBuffer, table: Int, size: Int): Map<Int, String> {
        if (table + 6 > size) return emptyMap()
        val format = bb.getUShort(table)
        val count = bb.getUShort(table + 2)
        val stringOffset = bb.getUShort(table + 4)
        val out = HashMap<Int, String>()
        for (i in 0 until count) {
            val rec = table + 6 + i * 12
            if (rec + 12 > size) break
            val platform = bb.getUShort(rec)
            val encoding = bb.getUShort(rec + 2)
            val nameId = bb.getUShort(rec + 6)
            val length = bb.getUShort(rec + 8)
            val offset = bb.getUShort(rec + 10)
            val start = table + stringOffset + offset
            if (start + length > size || nameId !in setOf(1, 2, 4, 17)) continue
            val raw = ByteArray(length) { bytesAt(bb, start + it, size) }
            val text = when {
                platform == 3 || (platform == 0 && encoding >= 4) ->
                    String(raw, Charsets.UTF_16BE).trim('\u0000')
                else -> String(raw, Charsets.ISO_8859_1).trim('\u0000')
            }
            if (text.isNotBlank() && !out.containsKey(nameId)) out[nameId] = text
        }
        return out
    }

    private fun bytesAt(bb: ByteBuffer, index: Int, size: Int): Byte =
        if (index in 0 until size) bb.get(index) else 0

    private fun parseFvar(
        bb: ByteBuffer,
        table: Int,
        size: Int,
        statTable: Int?
    ): Triple<Boolean, Float?, Float?>? {
        if (table + 16 > size) return null
        val axisCount = bb.getUShort(table + 8).toInt()
        val axisSize = bb.getUShort(table + 10).toInt()
        val firstAxis = table + 16
        val axisIndex = (0 until axisCount).firstOrNull { idx ->
            val off = firstAxis + idx * axisSize
            off + 20 <= size && String(bytesAtArray(bb, off, 4), Charsets.US_ASCII) == "wght"
        } ?: return Triple(false, null, null)
        val off = firstAxis + axisIndex * axisSize
        if (off + 20 > size) return null
        // fvar AxisRecord values are OpenType 16.16 Fixed, not IEEE floats.
        val min = bb.getInt(off + 8) / 65536f
        val max = bb.getInt(off + 16) / 65536f
        return Triple(true, min, max)
    }

    private fun bytesAtArray(bb: ByteBuffer, start: Int, length: Int): ByteArray =
        ByteArray(length) { bytesAt(bb, start + it, Int.MAX_VALUE) }

    private fun ByteBuffer.getUShort(index: Int): Int =
        (get(index).toInt() and 0xFF shl 8) or (get(index + 1).toInt() and 0xFF)
}
