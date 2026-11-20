package com.nadatube.app.extractor

import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamSelectorTest {
    private fun stream(itag: Int, codec: AudioCodec, kbps: Int, mime: String = if (codec == AudioCodec.OPUS) "audio/webm" else "audio/mp4") =
        AudioStream(itag, "u$itag", mime, codec, kbps * 1000, null, if (codec == AudioCodec.OPUS) 48_000 else 44_100, 2, null)

    private val youtube = listOf(
        stream(139, AudioCodec.AAC, 48), stream(140, AudioCodec.AAC, 128),
        stream(249, AudioCodec.OPUS, 50), stream(250, AudioCodec.OPUS, 70), stream(251, AudioCodec.OPUS, 160),
    )

    @Test
    fun `best quality picks opus 160 over aac 128`() {
        assertEquals(251, AudioStreamSelector.selectForPlayback(youtube)!!.itag)
    }

    @Test
    fun `aac 256 premium stream beats opus 160`() {
        assertEquals(141, AudioStreamSelector.selectForPlayback(youtube + stream(141, AudioCodec.AAC, 256))!!.itag)
    }

    @Test
    fun `data saver picks lowest usable bitrate`() {
        assertEquals(139, AudioStreamSelector.selectForPlayback(youtube, StreamQuality.DATA_SAVER)!!.itag)
    }

    @Test
    fun `downloads prefer taggable m4a`() {
        val s = AudioStreamSelector.selectForDownload(youtube)!!
        assertEquals(140, s.itag)
        assertEquals("m4a", s.extension)
    }

    @Test
    fun `download falls back to opus when no aac`() {
        assertEquals(251, AudioStreamSelector.selectForDownload(youtube.filter { it.codec == AudioCodec.OPUS })!!.itag)
    }

    @Test
    fun `empty list selects nothing`() {
        assertNull(AudioStreamSelector.selectForPlayback(emptyList()))
    }

    @Test
    fun `audio-only saves most of the data of a 720p stream`() {
        val ratio = AudioStreamSelector.dataSavingRatio(youtube.last())
        assertTrue("ratio=$ratio", ratio in 0.9f..0.95f)
    }

    @Test
    fun `quality labels`() {
        val opus = youtube.last()
        assertEquals("160 kbps Opus", opus.qualityLabel)
        assertEquals("48 kHz", opus.sampleRateLabel)
        assertTrue(opus.isHighQuality)
        assertEquals("44.1 kHz", youtube[1].sampleRateLabel)
    }
}
