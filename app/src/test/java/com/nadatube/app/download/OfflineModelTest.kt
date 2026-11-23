package com.nadatube.app.download

import com.nadatube.app.data.db.DownloadEntity
import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.data.db.DownloadWithTrack
import com.nadatube.app.data.db.TrackEntity
import com.nadatube.app.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class OfflineModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val track = Track("9qnqYL0eNNI", "Yellow", "Coldplay", "Parachutes", 267_000, "https://img/x")

    @Test
    fun `track entity round trip keeps metadata`() {
        val e = TrackEntity.from(track)
        assertFalse(e.isFavorite)
        assertEquals(track, e.toTrack())
    }

    @Test
    fun `completed download maps to playable offline track with local cover`() {
        val row = DownloadWithTrack(
            DownloadEntity(
                "9qnqYL0eNNI", status = DownloadStatus.COMPLETED.name, progress = 1f, filePath = "/m/Coldplay - Yellow.m4a",
                coverPath = "/m/cover.jpg", mimeType = "audio/mp4", bitrateKbps = 128, sizeBytes = 4_000_000, createdAt = 1,
            ),
            TrackEntity.from(track),
        )
        val o = OfflineTrack.from(row)!!
        assertTrue(o.isPlayable)
        assertEquals("file:///m/cover.jpg", o.track.thumbnailUrl)
        assertEquals("AAC · 128 kbps", o.formatLabel)
    }

    @Test
    fun `in-progress and orphan downloads`() {
        val pending = OfflineTrack.from(DownloadWithTrack(DownloadEntity("9qnqYL0eNNI", status = "DOWNLOADING", progress = 1.7f, createdAt = 1), TrackEntity.from(track)))!!
        assertFalse(pending.isPlayable)
        assertEquals(1f, pending.progress)
        assertNull(OfflineTrack.from(DownloadWithTrack(DownloadEntity("x", createdAt = 1), null)))
        assertEquals(DownloadStatus.FAILED, DownloadStatus.of("garbage"))
    }

    @Test
    fun `file names are filesystem safe and unique by video id`() {
        assertEquals("Coldplay - Yellow [9qnqYL0eNNI].m4a", OfflineFiles.fileName(track, "m4a"))
        val nasty = track.copy(title = "A/B: C*D? \"E\" <F>|", artist = "X\\Y")
        val name = OfflineFiles.fileName(nasty, "webm")
        assertFalse(name.any { it in "\\/:*?\"<>|" })
        assertTrue(name.endsWith("[9qnqYL0eNNI].webm"))
        assertTrue(OfflineFiles.fileName(track.copy(title = "x".repeat(500)), "m4a").length < 110)
    }

    @Test
    fun `storage summary fractions and formatting`() {
        val s = StorageSummary(downloadsBytes = 100, downloadCount = 1, cacheBytes = 50, cacheLimitBytes = 200, freeBytes = 850)
        assertEquals(0.25f, s.cacheFraction, 0.0001f)
        assertEquals(0.15f, s.usedFraction, 0.0001f)
        assertEquals("1.5 MB", StorageSummary.formatBytes(1_572_864))
        assertEquals("2.0 GB", StorageSummary.formatBytes(2L shl 30))
        assertEquals("512 B", StorageSummary.formatBytes(512))
    }

    // ---- MP4 tagging on a synthetic (non-fragmented, moov-before-mdat) file

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply { writeInt(payload.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload) }
        return b.toByteArray()
    }

    @Test
    fun `mp4 tags are embedded and chunk offsets shifted`() {
        val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(4) + "isom".toByteArray())
        val audio = "AUDIODATA".toByteArray()
        fun build(offset: Int): ByteArray {
            val stco = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset).array())
            val moov = box("moov", box("mvhd", ByteArray(100)) + box("trak", box("mdia", box("minf", box("stbl", stco)))))
            return ftyp + moov
        }
        val head = build(0)
        val file = tmp.newFile("t.m4a")
        val dataOffset = head.size + 8
        file.writeBytes(build(dataOffset) + box("mdat", audio))

        Mp4TagWriter.write(file, Mp4TagWriter.Tags("Yellow", "Coldplay", "Parachutes", byteArrayOf(1, 2, 3)))

        val tags = Mp4TagWriter.readTextTags(file)
        assertEquals("Yellow", tags["\u00A9nam"])
        assertEquals("Coldplay", tags["\u00A9ART"])
        assertEquals("Parachutes", tags["\u00A9alb"])
        assertEquals("<binary:3>", tags["covr"])

        // stco must still point at the audio payload after moov grew.
        val bytes = file.readBytes()
        val stcoPos = String(bytes, Charsets.ISO_8859_1).indexOf("stco") - 4
        val newOffset = ByteBuffer.wrap(bytes).getInt(stcoPos + 16)
        assertEquals("AUDIODATA", String(bytes, newOffset, audio.size))
    }

    @Test
    fun `retagging replaces existing udta instead of duplicating`() {
        val file = tmp.newFile("f.m4a")
        file.writeBytes(box("ftyp", ByteArray(8)) + box("moov", box("mvhd", ByteArray(20)) + box("mvex", ByteArray(0))) + box("mdat", ByteArray(4)))
        Mp4TagWriter.write(file, Mp4TagWriter.Tags("One", "A", null, null))
        Mp4TagWriter.write(file, Mp4TagWriter.Tags("Two", "B", null, null))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertEquals(1, Regex("udta").findAll(text).count())
        assertEquals("Two", Mp4TagWriter.readTextTags(file)["\u00A9nam"])
    }
}
