package com.nadatube.desktop.audio

/**
 * Minimal fragmented-MP4 (fMP4 / DASH) demuxer for YouTube AAC streams (itag 140).
 *
 * YouTube serves AAC as fragmented MP4: an init segment (`ftyp` + `moov`) followed by
 * media segments (`moof` + `mdat`). JavaSound cannot play AAC at all, and JAAD ships its
 * own MP4 reader that does not handle fragmented files, so this demuxer extracts the raw
 * AAC frames (with their durations) and hands them to [AacDecoder] (JAAD).
 *
 * Progressive (non-fragmented) MP4 files — e.g. files produced by ffmpeg without
 * `-movflags frag_keyframe` — are handled too, via the `stbl` tables in `moov`.
 *
 * Only the audio track (`hdlr` == "soun") is demuxed. Video tracks are ignored.
 */
object Fmp4Demuxer {

    data class AacFrame(val data: ByteArray, val durationSamples: Int, val timestampUs: Long)

    data class AacTrack(
        /** AudioSpecificConfig bytes from the `esds` box; fed to the JAAD decoder. */
        val audioSpecificConfig: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val frames: List<AacFrame>,
        val durationUs: Long,
    ) {
        val frameCount: Int get() = frames.size
        val durationMs: Long get() = durationUs / 1000
    }

    class DemuxException(message: String) : Exception(message)

    fun demux(bytes: ByteArray): AacTrack {
        val r = Reader(bytes)
        var moov: MoovInfo? = null
        val fragments = ArrayList<Fragment>()
        var mdatSeen = false
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            when (box.type) {
                "moov" -> moov = parseMoov(r.sliceTo(box.end))
                "moof" -> fragments += parseMoof(r.sliceTo(box.end), box.start)
                "mdat" -> mdatSeen = true
                else -> Unit
            }
            r.skipTo(box.end)
        }
        val info = moov ?: throw DemuxException("moov box tidak ditemukan")
        val audio = info.audio ?: throw DemuxException("track audio (soun) tidak ditemukan")
        val asc = audio.asc ?: throw DemuxException("AudioSpecificConfig (esds) tidak ditemukan")
        val (rate, channels) = parseAsc(asc)
        val frames = ArrayList<AacFrame>()
        if (fragments.isNotEmpty()) {
            var timeUs = 0L
            for (frag in fragments) {
                for (s in frag.samples) {
                    val data = bytes.copyOfRange(s.offset, (s.offset + s.size).coerceAtMost(bytes.size))
                    if (data.size != s.size) throw DemuxException("sample di luar file (offset=${s.offset} size=${s.size})")
                    val durSamples = (s.duration.toLong() * rate / audio.timescale).toInt().coerceAtLeast(1)
                    frames += AacFrame(data, durSamples, timeUs)
                    timeUs += durSamples * 1_000_000L / rate
                }
            }
        } else {
            // Progressive MP4: samples live in chunks described by stbl.
            if (!mdatSeen) throw DemuxException("mdat box tidak ditemukan")
            frames += progressiveFrames(bytes, audio)
        }
        if (frames.isEmpty()) throw DemuxException("tidak ada frame AAC")
        val durationUs = frames.sumOf { it.durationSamples.toLong() } * 1_000_000L / rate
        return AacTrack(asc, rate, channels, frames, durationUs)
    }

    // ------------------------------------------------------------------ boxes

    private class Reader(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        fun remaining(): Int = end - pos
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long {
            var v = 0L
            repeat(4) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun u64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun bytes(n: Int): ByteArray = bytes.copyOfRange(pos, pos + n).also { pos += n }
        fun str(n: Int): String = String(bytes(n), Charsets.US_ASCII)
        fun skip(n: Int) {
            pos = (pos + n).coerceAtMost(end)
        }

        fun skipTo(p: Int) {
            pos = p.coerceAtMost(end)
        }

        fun sliceTo(endPos: Int): Reader = Reader(bytes, pos, endPos.coerceAtMost(end))

        data class Box(val type: String, val start: Int, val end: Int, val size: Int)

        /** Reads [size, type] at the current position; caller must skipTo(box.end). */
        fun boxHeader(): Box? {
            if (remaining() < 8) return null
            val start = pos
            var size = u32().toInt()
            val type = str(4)
            var header = 8
            if (size == 1) {
                size = u64().toInt()
                header = 16
            } else if (size == 0) {
                size = end - start
            }
            if (size < header) return null
            return Box(type, start, start + size, size)
        }
    }

    private class MoovInfo(var timescale: Long = 0, var audio: AudioTrackInfo? = null)

    private class AudioTrackInfo(
        var timescale: Long = 0,
        var asc: ByteArray? = null,
        var sampleRate: Int = 0,
        var channels: Int = 0,
        var sampleCount: Int = 0,
        var sampleSizes: IntArray = intArrayOf(),
        var sampleDeltas: IntArray = intArrayOf(),
        var chunkOffsets: LongArray = longArrayOf(),
        var samplesPerChunk: IntArray = intArrayOf(),
        var firstChunk: IntArray = intArrayOf(),
    )

    private class Sample(val offset: Int, val size: Int, val duration: Long)
    private class Fragment(val samples: List<Sample>)

    private fun parseMoov(r: Reader): MoovInfo {
        val info = MoovInfo()
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            when (box.type) {
                "mvhd" -> info.timescale = parseMvhd(r.sliceTo(box.end))
                "trak" -> parseTrak(r.sliceTo(box.end))?.let { info.audio = it }
            }
            r.skipTo(box.end)
        }
        return info
    }

    private fun parseMvhd(r: Reader): Long {
        val version = r.u8()
        r.skip(3)
        return if (version == 1) {
            r.skip(8 + 8)
            r.u32()
        } else {
            r.skip(4 + 4)
            r.u32()
        }
    }

    private fun parseTrak(r: Reader): AudioTrackInfo? {
        var mdia: Reader? = null
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            if (box.type == "mdia") mdia = r.sliceTo(box.end)
            r.skipTo(box.end)
        }
        if (mdia == null) return null
        var handler: String? = null
        var mdhdTimescale = 0L
        var minf: Reader? = null
        while (mdia.remaining() >= 8) {
            val box = mdia.boxHeader() ?: break
            when (box.type) {
                "mdhd" -> mdhdTimescale = parseMdhd(mdia.sliceTo(box.end))
                "hdlr" -> handler = parseHdlr(mdia.sliceTo(box.end))
                "minf" -> minf = mdia.sliceTo(box.end)
            }
            mdia.skipTo(box.end)
        }
        if (handler != "soun" || minf == null) return null
        val track = AudioTrackInfo(timescale = mdhdTimescale)
        while (minf.remaining() >= 8) {
            val box = minf.boxHeader() ?: break
            if (box.type == "stbl") parseStbl(minf.sliceTo(box.end), track)
            minf.skipTo(box.end)
        }
        return track
    }

    private fun parseMdhd(r: Reader): Long {
        val version = r.u8()
        r.skip(3)
        return if (version == 1) {
            r.skip(8 + 8)
            r.u32()
        } else {
            r.skip(4 + 4)
            r.u32()
        }
    }

    private fun parseHdlr(r: Reader): String {
        r.skip(4)
        r.skip(4) // pre_defined
        return r.str(4)
    }

    private fun parseStbl(r: Reader, track: AudioTrackInfo) {
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            when (box.type) {
                "stsd" -> parseStsd(r.sliceTo(box.end), track)
                "stts" -> track.sampleDeltas = parseTts(r.sliceTo(box.end))
                "stsz" -> track.sampleSizes = parseStsz(r.sliceTo(box.end))
                "stsc" -> {
                    val (first, per) = parseStsc(r.sliceTo(box.end))
                    track.firstChunk = first
                    track.samplesPerChunk = per
                }
                "stco" -> track.chunkOffsets = parseStco(r.sliceTo(box.end), 32)
                "co64" -> track.chunkOffsets = parseStco(r.sliceTo(box.end), 64)
            }
            r.skipTo(box.end)
        }
    }

    private fun parseStsd(r: Reader, track: AudioTrackInfo) {
        r.skip(4 + 4) // version/flags + entry_count
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            if (box.type == "mp4a") {
                val e = r.sliceTo(box.end)
                e.skip(6 + 2 + 2 + 2 + 4) // reserved, data ref, version, revision, vendor
                track.channels = e.u16()
                e.skip(2) // samplesize
                e.skip(2 + 2) // pre_defined, reserved
                track.sampleRate = (e.u32() shr 16).toInt()
                while (e.remaining() >= 8) {
                    val inner = e.boxHeader() ?: break
                    if (inner.type == "esds") track.asc = parseEsds(e.sliceTo(inner.end))
                    e.skipTo(inner.end)
                }
            }
            r.skipTo(box.end)
        }
    }

    private fun parseEsds(r: Reader): ByteArray? {
        r.skip(4)
        var tag = r.u8()
        if (tag != 0x03) return null
        readDescriptorLength(r)
        r.skip(2 + 1) // ES_ID + flags
        tag = r.u8()
        if (tag != 0x04) return null
        readDescriptorLength(r)
        r.skip(1 + 1 + 3 + 4 + 4) // objectType, streamType, bufferSize, maxBitrate, avgBitrate
        tag = r.u8()
        if (tag != 0x05) return null
        val len = readDescriptorLength(r)
        return r.bytes(len)
    }

    /** Variable-length descriptor size: 7 bits per byte, high bit = continuation. */
    private fun readDescriptorLength(r: Reader): Int {
        var len = 0
        repeat(4) {
            val b = r.u8()
            len = (len shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return len
        }
        return len
    }

    private fun parseTts(r: Reader): IntArray {
        r.skip(4)
        val count = r.u32().toInt()
        val deltas = ArrayList<Int>()
        repeat(count) {
            val n = r.u32().toInt()
            val d = r.u32().toInt()
            repeat(n) { deltas += d }
        }
        return deltas.toIntArray()
    }

    private fun parseStsz(r: Reader): IntArray {
        r.skip(4)
        val uniform = r.u32().toInt()
        val count = r.u32().toInt()
        if (uniform != 0) return IntArray(count) { uniform }
        return IntArray(count) { r.u32().toInt() }
    }

    private fun parseStsc(r: Reader): Pair<IntArray, IntArray> {
        r.skip(4)
        val count = r.u32().toInt()
        val first = IntArray(count)
        val per = IntArray(count)
        repeat(count) { i ->
            first[i] = r.u32().toInt()
            per[i] = r.u32().toInt()
            r.skip(4) // sample_description_index
        }
        return first to per
    }

    private fun parseStco(r: Reader, bits: Int): LongArray {
        r.skip(4)
        val count = r.u32().toInt()
        return LongArray(count) { if (bits == 64) r.u64() else r.u32() }
    }

    private fun parseMoof(r: Reader, moofStart: Int): Fragment {
        val samples = ArrayList<Sample>()
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            if (box.type == "traf") samples += parseTraf(r.sliceTo(box.end), moofStart)
            r.skipTo(box.end)
        }
        return Fragment(samples)
    }

    private fun parseTraf(r: Reader, moofStart: Int): List<Sample> {
        var tfhd: Tfhd? = null
        var tfdt = 0L
        val truns = ArrayList<Trun>()
        while (r.remaining() >= 8) {
            val box = r.boxHeader() ?: break
            when (box.type) {
                "tfhd" -> tfhd = parseTfhd(r.sliceTo(box.end))
                "tfdt" -> tfdt = parseTfdt(r.sliceTo(box.end))
                "trun" -> truns += parseTrun(r.sliceTo(box.end))
            }
            r.skipTo(box.end)
        }
        val hd = tfhd ?: return emptyList()
        val out = ArrayList<Sample>()
        // Base offset for implicit sample offsets: tfhd base-data-offset, else moof start.
        // A trun's data-offset is relative to that base; without one, data continues
        // where the previous trun's data ended.
        val base: Long = hd.baseDataOffset ?: moofStart.toLong()
        var cursor: Long = base
        for (trun in truns) {
            var offset: Long = trun.dataOffset?.let { base + it } ?: cursor
            for (i in trun.samples.indices) {
                val s = trun.samples[i]
                val size = s.size ?: hd.defaultSampleSize ?: 0
                val duration = s.duration ?: hd.defaultSampleDuration ?: 1024L
                out += Sample(offset.toInt(), size, duration)
                offset += size
            }
            cursor = offset // next trun without data-offset continues here
        }
        @Suppress("UNUSED_VARIABLE")
        val unused = tfdt
        return out
    }

    private class Tfhd(
        val baseDataOffset: Long?,
        val defaultSampleDuration: Long?,
        val defaultSampleSize: Int?,
    )

    private fun parseTfhd(r: Reader): Tfhd {
        r.u8() // version
        val flags = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
        r.skip(4) // track_ID
        var baseDataOffset: Long? = null
        if (flags and 0x000001 != 0) baseDataOffset = r.u64()
        if (flags and 0x000002 != 0) r.skip(4) // sample_description_index
        var defaultDuration: Long? = null
        if (flags and 0x000008 != 0) defaultDuration = r.u32()
        var defaultSize: Int? = null
        if (flags and 0x000010 != 0) defaultSize = r.u32().toInt()
        if (flags and 0x000020 != 0) r.skip(4) // default_sample_flags
        return Tfhd(baseDataOffset, defaultDuration, defaultSize)
    }

    private class Trun(val dataOffset: Int?, val samples: List<TrunSample>)
    private class TrunSample(val duration: Long?, val size: Int?)

    private fun parseTfdt(r: Reader): Long {
        val version = r.u8()
        r.skip(3)
        return if (version == 1) r.u64() else r.u32()
    }

    private fun parseTrun(r: Reader): Trun {
        val version = r.u8()
        @Suppress("UNUSED_VARIABLE")
        val unusedVersion = version
        val b1 = r.u8()
        val b2 = r.u8()
        val b3 = r.u8()
        val flags = (b1 shl 16) or (b2 shl 8) or b3
        val count = r.u32().toInt()
        var dataOffset: Int? = null
        if (flags and 0x000001 != 0) dataOffset = r.u32().toInt()
        if (flags and 0x000004 != 0) r.skip(4) // first_sample_flags
        val samples = ArrayList<TrunSample>()
        repeat(count) {
            var duration: Long? = null
            var size: Int? = null
            if (flags and 0x000100 != 0) duration = r.u32()
            if (flags and 0x000200 != 0) size = r.u32().toInt()
            if (flags and 0x000400 != 0) r.skip(4) // sample_flags
            if (flags and 0x000800 != 0) r.skip(4) // sample_composition_time_offset
            samples += TrunSample(duration, size)
        }
        return Trun(dataOffset, samples)
    }

    private fun progressiveFrames(bytes: ByteArray, track: AudioTrackInfo): List<AacFrame> {
        val sizes = track.sampleSizes
        val deltas = track.sampleDeltas
        val chunks = track.chunkOffsets
        if (sizes.isEmpty() || chunks.isEmpty()) throw DemuxException("tabel stbl tidak lengkap")
        val out = ArrayList<AacFrame>()
        var sampleIdx = 0
        var timeUs = 0L
        val rate = track.sampleRate.takeIf { it > 0 } ?: 44100
        for ((chunkIdx, chunkOffset) in chunks.withIndex()) {
            val per = samplesInChunk(track, chunkIdx + 1)
            var offset = chunkOffset.toInt()
            repeat(per) {
                if (sampleIdx >= sizes.size) return@repeat
                val size = sizes[sampleIdx]
                val delta = deltas.getOrNull(sampleIdx) ?: 1024
                val durSamples = (delta.toLong() * rate / track.timescale).toInt().coerceAtLeast(1)
                val data = bytes.copyOfRange(offset, (offset + size).coerceAtMost(bytes.size))
                if (data.size != size) throw DemuxException("sample di luar file")
                out += AacFrame(data, durSamples, timeUs)
                timeUs += durSamples * 1_000_000L / rate
                offset += size
                sampleIdx++
            }
        }
        return out
    }

    private fun samplesInChunk(track: AudioTrackInfo, chunk1Based: Int): Int {
        val first = track.firstChunk
        if (first.isEmpty()) return track.samplesPerChunk.getOrNull(0) ?: 0
        var idx = 0
        for (i in first.indices) {
            if (first[i] <= chunk1Based) idx = i else break
        }
        return track.samplesPerChunk.getOrElse(idx) { 0 }
    }

    // ------------------------------------------------------------- ASC parsing

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350,
    )

    /** Returns (sampleRate, channels) from an AudioSpecificConfig. */
    fun parseAsc(asc: ByteArray): Pair<Int, Int> {
        if (asc.size < 2) throw DemuxException("ASC terlalu pendek")
        var bitPos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = asc[bitPos / 8].toInt()
                val bit = (byte shr (7 - (bitPos % 8))) and 1
                v = (v shl 1) or bit
                bitPos++
            }
            return v
        }
        var objectType = bits(5)
        var freqIndex = bits(4)
        var sampleRate = if (freqIndex == 15) bits(24) else SAMPLE_RATES.getOrElse(freqIndex) { 0 }
        var channels = bits(4)
        if (objectType == 5) { // SBR: explicit sampling rate + extension object type
            val extFreqIndex = bits(4)
            sampleRate = if (extFreqIndex == 15) bits(24) else SAMPLE_RATES.getOrElse(extFreqIndex) { sampleRate }
            objectType = bits(5)
        }
        if (sampleRate <= 0) throw DemuxException("sample rate tidak dikenal di ASC")
        if (channels == 0) channels = 2
        return sampleRate to channels
    }
}
