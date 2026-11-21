package com.nadatube.desktop.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Membuktikan JAAD benar-benar mendekode AAC menjadi PCM yang isinya nada 440 Hz —
 * bukan sekadar "tidak error". Frekuensi dominan dicek dengan algoritma Goertzel
 * pada kanal kiri satu frame di tengah file (frame 10, bebas priming encoder).
 */
class AacDecoderTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    private fun demuxed() = Fmp4Demuxer.demux(fixture("tone-440-aac-frag.m4a"))

    @Test
    fun `decoder reports 44100 Hz stereo`() {
        val track = demuxed()
        AacDecoder(track.audioSpecificConfig).use { decoder ->
            assertEquals(44100, decoder.sampleRate)
            assertEquals(2, decoder.channels)
        }
    }

    @Test
    fun `one frame decodes to 1024 stereo 16-bit samples`() {
        val track = demuxed()
        AacDecoder(track.audioSpecificConfig).use { decoder ->
            val pcm = decoder.decodeFrame(track.frames[10].data)
            assertEquals(1024 * 2 * 2, pcm.size)
        }
    }

    @Test
    fun `decoded frame is dominated by 440 Hz`() {
        val track = demuxed()
        AacDecoder(track.audioSpecificConfig).use { decoder ->
            // Lewati frame awal (priming), ambil frame ke-10.
            repeat(10) { decoder.decodeFrame(track.frames[it].data) }
            val pcm = decoder.decodeFrame(track.frames[10].data)
            val mag440 = goertzelMag(pcm, 44100, 440.0)
            val mag880 = goertzelMag(pcm, 44100, 880.0)
            val mag1000 = goertzelMag(pcm, 44100, 1000.0)
            assertTrue("mag440=$mag440 mag880=$mag880 mag1000=$mag1000", mag440 > mag880 * 4)
            assertTrue("mag440=$mag440 mag1000=$mag1000", mag440 > mag1000 * 4)
        }
    }

    @Test
    fun `all 88 frames decode to the expected PCM byte count`() {
        val track = demuxed()
        var total = 0L
        AacDecoder(track.audioSpecificConfig).use { decoder ->
            for (frame in track.frames) total += decoder.decodeFrame(frame.data).size
        }
        assertEquals(88L * 1024 * 2 * 2, total)
    }

    /** Magnitudo Goertzel pada kanal kiri (PCM 16-bit little-endian, interleaved stereo). */
    private fun goertzelMag(pcm: ByteArray, sampleRate: Int, freq: Double): Double {
        val n = pcm.size / 4 // frame stereo: 4 byte per sampel (2 kanal x 2 byte)
        val k = (0.5 + n * freq / sampleRate).toInt()
        val w = 2 * PI * k / n
        val cw = cos(w)
        val coeff = 2 * cw
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until n) {
            val lo = pcm[i * 4].toInt() and 0xFF
            val hi = pcm[i * 4 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble() / Short.MAX_VALUE
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cw
        val imag = q2 * kotlin.math.sin(w)
        return sqrt(real * real + imag * imag)
    }
}
