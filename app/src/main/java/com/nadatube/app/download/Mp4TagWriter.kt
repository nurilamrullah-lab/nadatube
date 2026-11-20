package com.nadatube.app.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Embeds iTunes-style metadata (title, artist, album, cover art) into an M4A file — the MP4
 * equivalent of ID3 tags, read by Android's MediaMetadataRetriever and every music player.
 *
 * The `moov` box is rebuilt with a fresh `udta/meta/ilst`. When `moov` sits before `mdat` in a
 * non-fragmented file, absolute chunk offsets (`stco`/`co64`) are shifted accordingly.
 * Fragmented files (YouTube DASH) use moof-relative offsets and need no adjustment.
 */
object Mp4TagWriter {
    data class Tags(val title: String, val artist: String, val album: String?, val cover: ByteArray?, val coverIsPng: Boolean = false)

    private class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    fun write(file: File, tags: Tags) {
        val out = File(file.parentFile, file.name + ".tagging")
        RandomAccessFile(file, "r").use { raf ->
            val top = readBoxes(raf, 0, raf.length())
            val moov = top.firstOrNull { it.type == "moov" } ?: error("Bukan file MP4: moov tidak ditemukan")
            val moovBytes = ByteArray(moov.size.toInt()).also { raf.seek(moov.offset); raf.readFully(it) }
            val fragmented = containsChild(moovBytes, "mvex")
            val newMoov = rebuildMoov(moovBytes, tags)
            val delta = (newMoov.size - moovBytes.size).toLong()
            val mdatAfterMoov = top.any { it.type == "mdat" && it.offset > moov.offset }
            val patched = if (!fragmented && mdatAfterMoov && delta != 0L) shiftChunkOffsets(newMoov, delta) else newMoov

            RandomAccessFile(out, "rw").use { dst ->
                dst.setLength(0)
                val src = raf.channel
                val ch = dst.channel
                var pos = 0L
                for (b in top) {
                    if (b === moov) {
                        ch.write(ByteBuffer.wrap(patched))
                    } else {
                        var copied = 0L
                        while (copied < b.size) copied += src.transferTo(b.offset + copied, b.size - copied, ch)
                    }
                    pos += b.size
                }
            }
        }
        if (!out.renameTo(file)) {
            out.copyTo(file, overwrite = true)
            out.delete()
        }
    }

    // ---------------------------------------------------------------- box parsing

    private fun readBoxes(raf: RandomAccessFile, start: Long, end: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header || pos + size > end) break
            boxes += Box(type, pos, size, header)
            pos += size
        }
        return boxes
    }

    private fun children(data: ByteArray, start: Int, end: Int): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var pos = start
        val buf = ByteBuffer.wrap(data)
        while (pos + 8 <= end) {
            val size = buf.getInt(pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || pos + size > end) break
            out += Triple(type, pos, size)
            pos += size
        }
        return out
    }

    private fun containsChild(moov: ByteArray, type: String) = children(moov, 8, moov.size).any { it.first == type }

    private fun rebuildMoov(moov: ByteArray, tags: Tags): ByteArray {
        val body = ByteArrayOutputStream()
        for ((type, pos, size) in children(moov, 8, moov.size)) {
            if (type != "udta") body.write(moov, pos, size)
        }
        body.write(udta(tags))
        return box("moov", body.toByteArray())
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    private fun shiftChunkOffsets(moov: ByteArray, delta: Long): ByteArray {
        val buf = ByteBuffer.wrap(moov)
        fun walk(start: Int, end: Int) {
            for ((type, pos, size) in children(moov, start, end)) {
                when (type) {
                    in CONTAINERS -> walk(pos + 8, pos + size)
                    "stco" -> {
                        val count = buf.getInt(pos + 12)
                        for (i in 0 until count) {
                            val p = pos + 16 + i * 4
                            buf.putInt(p, ((buf.getInt(p).toLong() and 0xFFFFFFFFL) + delta).toInt())
                        }
                    }
                    "co64" -> {
                        val count = buf.getInt(pos + 12)
                        for (i in 0 until count) {
                            val p = pos + 16 + i * 8
                            buf.putLong(p, buf.getLong(p) + delta)
                        }
                    }
                }
            }
        }
        walk(8, moov.size)
        return moov
    }

    // ---------------------------------------------------------------- box building

    private fun box(type: String, payload: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(payload.size + 8)
        DataOutputStream(bos).apply {
            writeInt(payload.size + 8)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }
        return bos.toByteArray()
    }

    private fun dataAtom(name: String, typeFlag: Int, value: ByteArray): ByteArray {
        val payload = ByteBuffer.allocate(8 + value.size).putInt(typeFlag).putInt(0).put(value).array()
        return box(name, box("data", payload))
    }

    private fun udta(tags: Tags): ByteArray {
        val ilst = ByteArrayOutputStream()
        ilst.write(dataAtom("\u00A9nam", 1, tags.title.toByteArray(Charsets.UTF_8)))
        ilst.write(dataAtom("\u00A9ART", 1, tags.artist.toByteArray(Charsets.UTF_8)))
        ilst.write(dataAtom("aART", 1, tags.artist.toByteArray(Charsets.UTF_8)))
        tags.album?.let { ilst.write(dataAtom("\u00A9alb", 1, it.toByteArray(Charsets.UTF_8))) }
        ilst.write(dataAtom("\u00A9too", 1, "NadaTube".toByteArray(Charsets.UTF_8)))
        tags.cover?.let { ilst.write(dataAtom("covr", if (tags.coverIsPng) 14 else 13, it)) }

        val hdlr = ByteBuffer.allocate(25)
            .putInt(0).putInt(0)
            .put("mdir".toByteArray(Charsets.ISO_8859_1))
            .put("appl".toByteArray(Charsets.ISO_8859_1))
            .putInt(0).putInt(0).put(0)
            .array()
        val meta = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0))
            write(box("hdlr", hdlr))
            write(box("ilst", ilst.toByteArray()))
        }
        return box("udta", box("meta", meta.toByteArray()))
    }

    /** Reads back ilst text atoms; used by tests and by the library to verify tagging. */
    fun readTextTags(file: File): Map<String, String> {
        val bytes = file.readBytes()
        val result = mutableMapOf<String, String>()
        fun walk(start: Int, end: Int) {
            for ((type, pos, size) in children(bytes, start, end)) {
                when (type) {
                    "moov", "udta" -> walk(pos + 8, pos + size)
                    "meta" -> walk(pos + 12, pos + size)
                    "ilst" -> for ((name, p, s) in children(bytes, pos + 8, pos + size)) {
                        val data = children(bytes, p + 8, p + s).firstOrNull { it.first == "data" } ?: continue
                        val flag = ByteBuffer.wrap(bytes).getInt(data.second + 8)
                        val valueStart = data.second + 16
                        val valueLen = data.third - 16
                        result[name] = if (flag == 1) String(bytes, valueStart, valueLen, Charsets.UTF_8) else "<binary:$valueLen>"
                    }
                }
            }
        }
        walk(0, bytes.size)
        return result
    }
}
