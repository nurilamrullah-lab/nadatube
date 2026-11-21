package com.nadatube.desktop.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture: nada sinus 440 Hz, 2,0 detik, AAC-LC 128 kbps, 44,1 kHz stereo, dibuat dengan ffmpeg.
 * `tone-440-aac-frag.m4a` = fMP4 terfragmentasi (seperti yang disajikan YouTube DASH);
 * `tone-440-aac.m4a` = MP4 progresif biasa.
 */
class Fmp4DemuxerTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    @Test
    fun `fragmented fMP4 has 88 AAC frames and about 2023 ms`() {
        val track = Fmp4Demuxer.demux(fixture("tone-440-aac-frag.m4a"))
        assertEquals(44100, track.sampleRate)
        assertEquals(2, track.channels)
        assertEquals(88, track.frameCount)
        assertTrue("durationMs=${track.durationMs}", track.durationMs in 2000..2050)
        assertTrue(track.audioSpecificConfig.isNotEmpty())
        // AAC-LC: tiap frame 1024 sampel, kecuali frame terakhir yang parsial.
        assertEquals(1024, track.frames[0].durationSamples)
        assertEquals(0L, track.frames[0].timestampUs)
        assertTrue(track.frames[87].timestampUs in 2_000_000..2_050_000)
        // Timestamp naik monoton.
        assertTrue(track.frames.zipWithNext().all { (a, b) -> b.timestampUs > a.timestampUs })
    }

    @Test
    fun `progressive MP4 parses through stbl tables`() {
        val track = Fmp4Demuxer.demux(fixture("tone-440-aac.m4a"))
        assertEquals(44100, track.sampleRate)
        assertEquals(2, track.channels)
        assertTrue(track.frameCount in 85..90)
        assertTrue("durationMs=${track.durationMs}", track.durationMs in 2000..2050)
        assertTrue(track.audioSpecificConfig.isNotEmpty())
    }

    @Test
    fun `ASC parses to 44100 Hz stereo`() {
        val track = Fmp4Demuxer.demux(fixture("tone-440-aac-frag.m4a"))
        val (rate, channels) = Fmp4Demuxer.parseAsc(track.audioSpecificConfig)
        assertEquals(44100, rate)
        assertEquals(2, channels)
    }

    @Test
    fun `garbage input throws DemuxException`() {
        assertThrows(Fmp4Demuxer.DemuxException::class.java) {
            Fmp4Demuxer.demux("bukan mp4 sama sekali".toByteArray())
        }
        assertThrows(Fmp4Demuxer.DemuxException::class.java) {
            Fmp4Demuxer.demux(ByteArray(0))
        }
    }
}
