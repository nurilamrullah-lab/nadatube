package com.nadatube.desktop.audio

import java.nio.ByteBuffer

/** One encoded AAC access unit inside [AacTrack.data]. */
data class Sample(val offset: Int, val size: Int, val startTicks: Long, val durationTicks: Long)

class AacTrack(
    /** AudioSpecificConfig from `esds`, as the decoder wants it. */
    val config: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val timescale: Int,
    val samples: List<Sample>,
    val data: ByteArray,
) {
    val durationMs: Long
        get() = samples.lastOrNull()?.let { (it.startTicks + it.durationTicks) * 1000L / timescale } ?: 0L

    /** Index of the sample playing at [ms] (clamped). */
    fun indexAt(ms: Long): Int {
        if (samples.isEmpty()) return 0
        val ticks = ms.coerceAtLeast(0) * timescale / 1000
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (samples[mid].startTicks <= ticks) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun startMs(index: Int): Long = samples[index].startTicks * 1000L / timescale

    fun frame(index: Int): ByteArray = data.copyOfRange(samples[index].offset, samples[index].offset + samples[index].size)
}

class Mp4FormatException(message: String) : Exception(message)

/**
 * Minimal MP4 audio demuxer. YouTube serves AAC as *fragmented* MP4 (an empty `moov`, a `sidx`,
 * then `moof`+`mdat` pairs), which JAAD's own container reader does not handle; progressive
 * files (what most tools write) are supported too.
 */
object Mp4Demuxer {
    private class Box(val type: String, val start: Int, val bodyStart: Int, val end: Int)

    fun parse(data: ByteArray): AacTrack {
        val buf = ByteBuffer.wrap(data)
        val top = children(buf, 0, data.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw Mp4FormatException("moov tidak ditemukan")
        val trak = children(buf, moov.bodyStart, moov.end).filter { it.type == "trak" }
            .firstOrNull { find(buf, it, "mdia", "minf", "stbl", "stsd") != null && handler(buf, it) == "soun" }
            ?: throw Mp4FormatException("track audio tidak ditemukan")

        val mdhd = find(buf, trak, "mdia", "mdhd")!!
        val version = buf.get(mdhd.bodyStart).toInt()
        val timescale = buf.getInt(mdhd.bodyStart + if (version == 1) 20 else 12)
        val stsd = find(buf, trak, "mdia", "minf", "stbl", "stsd")!!
        val entry = children(buf, stsd.bodyStart + 8, stsd.end).firstOrNull { it.type == "mp4a" }
            ?: throw Mp4FormatException("bukan AAC (mp4a)")
        val channels = buf.getShort(entry.bodyStart + 16).toInt()
        val rateFromEntry = (buf.getInt(entry.bodyStart + 24) ushr 16)
        val esds = children(buf, entry.bodyStart + 28, entry.end).firstOrNull { it.type == "esds" }
            ?: throw Mp4FormatException("esds tidak ditemukan")
        val config = decoderSpecificInfo(data, esds.bodyStart + 4, esds.end)
        val (sampleRate, cfgChannels) = describe(config, rateFromEntry, channels)

        val stbl = find(buf, trak, "mdia", "minf", "stbl")!!
        val samples = mutableListOf<Sample>()
        val hasTable = children(buf, stbl.bodyStart, stbl.end).any { it.type == "stsz" && buf.getInt(it.bodyStart + 8) > 0 }
        if (hasTable) progressive(buf, stbl, samples)
        val trex = find(buf, moov, "mvex", "trex")
        val defaultDuration = trex?.let { buf.getInt(it.bodyStart + 12).toLong() } ?: 1024L
        val defaultSize = trex?.let { buf.getInt(it.bodyStart + 16) } ?: 0
        for (moof in top.filter { it.type == "moof" }) fragment(buf, moof, defaultDuration, defaultSize, samples)
        if (samples.isEmpty()) throw Mp4FormatException("tidak ada sample audio")
        samples.forEach {
            if (it.offset < 0 || it.offset + it.size > data.size) throw Mp4FormatException("sample di luar file (file terpotong?)")
        }
        return AacTrack(config, sampleRate, cfgChannels, timescale, samples, data)
    }

    // ---------------------------------------------------------------- fragmented

    private fun fragment(buf: ByteBuffer, moof: Box, defDuration: Long, defSize: Int, out: MutableList<Sample>) {
        for (traf in children(buf, moof.bodyStart, moof.end).filter { it.type == "traf" }) {
            val kids = children(buf, traf.bodyStart, traf.end)
            val tfhd = kids.firstOrNull { it.type == "tfhd" } ?: continue
            val flags = buf.getInt(tfhd.bodyStart) and 0xFFFFFF
            var p = tfhd.bodyStart + 8
            val baseOffset = if (flags and 0x1 != 0) buf.getLong(p).also { p += 8 } else moof.start.toLong()
            if (flags and 0x2 != 0) p += 4
            val fragDuration = if (flags and 0x8 != 0) buf.getInt(p).toLong().also { p += 4 } else defDuration
            val fragSize = if (flags and 0x10 != 0) buf.getInt(p).also { p += 4 } else defSize

            val tfdt = kids.firstOrNull { it.type == "tfdt" }
            var time = tfdt?.let {
                if (buf.get(it.bodyStart).toInt() == 1) buf.getLong(it.bodyStart + 4) else buf.getInt(it.bodyStart + 4).toLong() and 0xFFFFFFFFL
            } ?: out.lastOrNull()?.let { it.startTicks + it.durationTicks } ?: 0L

            for (trun in kids.filter { it.type == "trun" }) {
                val tf = buf.getInt(trun.bodyStart) and 0xFFFFFF
                val count = buf.getInt(trun.bodyStart + 4)
                var q = trun.bodyStart + 8
                var offset = baseOffset
                if (tf and 0x1 != 0) { offset += buf.getInt(q); q += 4 } else offset = baseOffset + (moof.end - moof.start) + 8
                if (tf and 0x4 != 0) q += 4
                repeat(count) {
                    val dur = if (tf and 0x100 != 0) buf.getInt(q).toLong().also { q += 4 } else fragDuration
                    val size = if (tf and 0x200 != 0) buf.getInt(q).also { q += 4 } else fragSize
                    if (tf and 0x400 != 0) q += 4
                    if (tf and 0x800 != 0) q += 4
                    out += Sample(offset.toInt(), size, time, dur)
                    offset += size
                    time += dur
                }
            }
        }
    }

    // ---------------------------------------------------------------- progressive

    private fun progressive(buf: ByteBuffer, stbl: Box, out: MutableList<Sample>) {
        val kids = children(buf, stbl.bodyStart, stbl.end)
        val stsz = kids.first { it.type == "stsz" }
        val fixed = buf.getInt(stsz.bodyStart + 4)
        val count = buf.getInt(stsz.bodyStart + 8)
        val sizes = IntArray(count) { if (fixed != 0) fixed else buf.getInt(stsz.bodyStart + 12 + it * 4) }
        val co = kids.firstOrNull { it.type == "stco" || it.type == "co64" } ?: throw Mp4FormatException("stco hilang")
        val chunks = buf.getInt(co.bodyStart + 4)
        val chunkOffsets = LongArray(chunks) {
            if (co.type == "co64") buf.getLong(co.bodyStart + 8 + it * 8) else buf.getInt(co.bodyStart + 8 + it * 4).toLong() and 0xFFFFFFFFL
        }
        val stsc = kids.first { it.type == "stsc" }
        val entries = buf.getInt(stsc.bodyStart + 4)
        val firstChunk = IntArray(entries) { buf.getInt(stsc.bodyStart + 8 + it * 12) }
        val perChunk = IntArray(entries) { buf.getInt(stsc.bodyStart + 12 + it * 12) }
        val durations = LongArray(count) { 1024L }
        kids.firstOrNull { it.type == "stts" }?.let { stts ->
            var i = 0
            repeat(buf.getInt(stts.bodyStart + 4)) { e ->
                val n = buf.getInt(stts.bodyStart + 8 + e * 8)
                val d = buf.getInt(stts.bodyStart + 12 + e * 8).toLong()
                repeat(n) { if (i < count) durations[i++] = d }
            }
        }
        var sample = 0
        var time = 0L
        for (c in 0 until chunks) {
            val e = (entries - 1 downTo 0).first { firstChunk[it] <= c + 1 }
            var offset = chunkOffsets[c]
            repeat(perChunk[e]) {
                if (sample >= count) return@repeat
                out += Sample(offset.toInt(), sizes[sample], time, durations[sample])
                offset += sizes[sample]
                time += durations[sample]
                sample++
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun children(buf: ByteBuffer, start: Int, end: Int): List<Box> {
        val out = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            var size = buf.getInt(pos).toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4) { buf.get(pos + 4 + it) }, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = buf.getLong(pos + 8); header = 16 } else if (size == 0L) size = (end - pos).toLong()
            if (size < header || pos + size > end) break
            out += Box(type, pos, pos + header, (pos + size).toInt())
            pos += size.toInt()
        }
        return out
    }

    private fun find(buf: ByteBuffer, from: Box, vararg path: String): Box? {
        var cur = from
        for (name in path) cur = children(buf, cur.bodyStart, cur.end).firstOrNull { it.type == name } ?: return null
        return cur
    }

    private fun handler(buf: ByteBuffer, trak: Box): String? = find(buf, trak, "mdia", "hdlr")?.let {
        String(ByteArray(4) { i -> buf.get(it.bodyStart + 8 + i) }, Charsets.ISO_8859_1)
    }

    /** esds → ES_Descriptor(3) → DecoderConfigDescriptor(4) → DecoderSpecificInfo(5). */
    private fun decoderSpecificInfo(d: ByteArray, start: Int, end: Int): ByteArray {
        var p = start
        fun len(): Int { var n = 0; repeat(4) { val b = d[p++].toInt() and 0xFF; n = (n shl 7) or (b and 0x7F); if (b and 0x80 == 0) return n }; return n }
        while (p < end) {
            val tag = d[p++].toInt() and 0xFF
            val l = len()
            when (tag) {
                0x03 -> { p += 2; val f = d[p++].toInt(); if (f and 0x80 != 0) p += 2; if (f and 0x40 != 0) p += 1 + (d[p].toInt() and 0xFF); if (f and 0x20 != 0) p += 2 }
                0x04 -> p += 13
                0x05 -> return d.copyOfRange(p, p + l)
                else -> p += l
            }
        }
        throw Mp4FormatException("AudioSpecificConfig tidak ditemukan")
    }

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Sample rate / channels as the decoder will output them (the ASC is authoritative). */
    private fun describe(asc: ByteArray, fallbackRate: Int, fallbackChannels: Int): Pair<Int, Int> {
        if (asc.size < 2) return fallbackRate to fallbackChannels
        val bits = ((asc[0].toInt() and 0xFF) shl 8) or (asc[1].toInt() and 0xFF)
        val rateIndex = (bits shr 7) and 0xF
        val ch = (bits shr 3) and 0xF
        val rate = RATES.getOrNull(rateIndex) ?: fallbackRate
        return rate to (if (ch in 1..7) (if (ch == 7) 8 else ch) else fallbackChannels)
    }
}
