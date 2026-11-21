package com.nadatube.desktop

import com.nadatube.desktop.audio.AacDecoder
import com.nadatube.desktop.audio.AacTrack
import com.nadatube.desktop.audio.AudioEngine
import com.nadatube.desktop.audio.Mp4Demuxer
import com.nadatube.desktop.audio.Mp4FormatException
import com.nadatube.desktop.audio.Pcm
import com.nadatube.desktop.audio.PcmSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Records PCM instead of playing it; "plays" instantly but reports realistic played time. */
class FakeSink(private val rate: Int, private val channels: Int) : PcmSink {
    val bytes = ByteArrayOutputStream()
    @Volatile var paused = false
    @Volatile var closed = false
    override fun write(pcm: ByteArray) { bytes.write(pcm) }
    override fun playedMs() = bytes.size().toLong() * 1000 / (rate * channels * 2)
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun close() { closed = true }
}

private fun resource(name: String) = AudioEngineTest::class.java.getResourceAsStream("/audio/$name")!!.readBytes()

/** Energy of [freq] in a 16-bit LE signal (Goertzel, first channel). */
fun toneEnergy(pcm: ByteArray, channels: Int, rate: Int, freq: Double): Double {
    val n = pcm.size / 2 / channels
    val w = 2 * PI * freq / rate
    var re = 0.0
    var im = 0.0
    for (i in 0 until n) {
        val s = ((pcm[i * channels * 2].toInt() and 0xFF) or (pcm[i * channels * 2 + 1].toInt() shl 8)).toShort() / 32768.0
        re += s * cos(w * i); im += s * sin(w * i)
    }
    return (re * re + im * im) / (n.toDouble() * n)
}

class AudioEngineTest {
    private fun decodeAll(t: AacTrack): ByteArray {
        val dec = AacDecoder(t.config)
        val out = ByteArrayOutputStream()
        for (i in t.samples.indices) out.write(dec.decode(t.frame(i)))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- demuxer

    @Test
    fun `fragmented mp4 like YouTube's is demuxed`() {
        for (name in listOf("tone440_fragmented.m4a", "tone440_fragmented_nodefbase.m4a")) {
            val t = Mp4Demuxer.parse(resource(name))
            assertEquals(44_100, t.sampleRate, name)
            assertEquals(2, t.channels, name)
            assertTrue(abs(t.durationMs - 2_023) < 40, "$name duration ${t.durationMs}")
            assertTrue(t.samples.size in 85..90, "$name samples ${t.samples.size}")
            assertTrue(t.samples.zipWithNext().all { (a, b) -> b.startTicks == a.startTicks + a.durationTicks }, "$name contiguous timeline")
            assertTrue(t.samples.all { it.size in 1..4000 })
        }
    }

    @Test
    fun `progressive mono file is demuxed`() {
        val t = Mp4Demuxer.parse(resource("tone880_mono_progressive.m4a"))
        assertEquals(48_000, t.sampleRate)
        assertEquals(1, t.channels)
        assertTrue(abs(t.durationMs - 1_500) < 60, "duration ${t.durationMs}")
    }

    @Test
    fun `index lookup is monotonic and clamped`() {
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        assertEquals(0, t.indexAt(-50))
        assertEquals(t.samples.lastIndex, t.indexAt(1_000_000))
        var last = -1
        for (ms in 0..2_100 step 50) { val i = t.indexAt(ms.toLong()); assertTrue(i >= last); last = i }
        val mid = t.indexAt(1_000)
        assertTrue(abs(t.startMs(mid) - 1_000) <= 24, "frame is ~23 ms wide")
    }

    @Test
    fun `truncated or non-audio files fail clearly`() {
        val whole = resource("tone440_fragmented.m4a")
        assertFailsWith<Mp4FormatException> { Mp4Demuxer.parse(whole.copyOf(whole.size - 5_000)) }
        assertFailsWith<Mp4FormatException> { Mp4Demuxer.parse("not an mp4 at all, just text".toByteArray()) }
    }

    // ---------------------------------------------------------------- decoder

    @Test
    fun `explicit no-SBR extension is trimmed, real SBR and HE-AAC are kept`() {
        val plain = byteArrayOf(0x12, 0x10)
        val ffmpegStyle = byteArrayOf(0x12, 0x10, 0x56, 0xE5.toByte(), 0x00)
        val withSbr = byteArrayOf(0x12, 0x10, 0x56, 0xE5.toByte(), 0x80.toByte())
        val heAac = byteArrayOf(0x2B, 0x92.toByte(), 0x08, 0x00)
        assertTrue(Mp4Demuxer.normalizeConfig(plain).contentEquals(plain))
        assertTrue(Mp4Demuxer.normalizeConfig(ffmpegStyle).contentEquals(plain))
        assertTrue(Mp4Demuxer.normalizeConfig(withSbr).contentEquals(withSbr))
        assertTrue(Mp4Demuxer.normalizeConfig(heAac).contentEquals(heAac))
    }

    @Test
    fun `decoded audio really is the 440 Hz tone, at full length`() {
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        val pcm = decodeAll(t)
        assertEquals(t.samples.size * 1024 * 2 * 2, pcm.size)
        val at440 = toneEnergy(pcm, 2, 44_100, 440.0)
        assertTrue(at440 > 0.001, "tone energy $at440 (a 0.087-amplitude sine gives (A/2)^2 ≈ 0.0019)")
        assertTrue(at440 > 100 * toneEnergy(pcm, 2, 44_100, 1_000.0), "440 must dominate 1 kHz")
        assertTrue(Pcm.rms(pcm) > 0.05, "not silence")
    }

    @Test
    fun `mono 48k decodes to the 880 Hz tone`() {
        val t = Mp4Demuxer.parse(resource("tone880_mono_progressive.m4a"))
        val pcm = decodeAll(t)
        assertTrue(toneEnergy(pcm, 1, 48_000, 880.0) > 100 * toneEnergy(pcm, 1, 48_000, 440.0))
    }

    @Test
    fun `volume scaling`() {
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        val pcm = decodeAll(t)
        val half = pcm.copyOf().also { Pcm.applyVolume(it, 0.5f) }
        assertEquals(0.5, Pcm.rms(half) / Pcm.rms(pcm), 0.01)
        val same = pcm.copyOf().also { Pcm.applyVolume(it, 1f) }
        assertTrue(same.contentEquals(pcm))
        val muted = pcm.copyOf().also { Pcm.applyVolume(it, 0f) }
        assertEquals(0.0, Pcm.rms(muted), 0.0)
    }

    // ---------------------------------------------------------------- engine

    private fun waitUntil(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(5) }
    }

    @Test
    fun `plays a track to the end and reports it`() {
        val sinks = mutableListOf<FakeSink>()
        val engine = AudioEngine { r, c -> FakeSink(r, c).also { sinks += it } }
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        engine.load(t)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(t.samples.size * 1024 * 4, sinks.single().bytes.size())
        assertEquals(t.durationMs, engine.state.value.positionMs)
        assertEquals(false, engine.state.value.playing)
    }

    @Test
    fun `seek restarts from the right frame and discards the old sink`() {
        val sinks = mutableListOf<FakeSink>()
        val engine = AudioEngine { r, c -> FakeSink(r, c).also { sinks += it } }
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        engine.load(t, startMs = 1_000)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        val expectedFrames = t.samples.size - t.indexAt(1_000)
        assertEquals(expectedFrames * 1024 * 4, sinks.last().bytes.size())
        assertEquals(t.durationMs, engine.state.value.positionMs)
    }

    @Test
    fun `pause stops output and resume continues`() {
        val frames = AtomicLong(0)
        val sinks = mutableListOf<FakeSink>()
        val engine = AudioEngine { r, c ->
            val fake = FakeSink(r, c).also { sinks += it }
            object : PcmSink by fake {
                override fun write(pcm: ByteArray) { fake.write(pcm); frames.incrementAndGet(); Thread.sleep(8) }
            }
        }
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        engine.load(t)
        waitUntil { frames.get() > 5 }
        engine.pause()
        Thread.sleep(80) // let the in-flight frame finish
        val frozen = frames.get()
        Thread.sleep(150)
        assertEquals(frozen, frames.get(), "no frames while paused")
        assertTrue(sinks.single().paused)
        assertEquals(false, engine.state.value.playing)
        engine.play()
        waitUntil { frames.get() > frozen + 5 }
        assertEquals(true, engine.state.value.playing)
        engine.stop()
    }

    @Test
    fun `volume changes apply to the frames that follow`() {
        val sinks = mutableListOf<FakeSink>()
        val engine = AudioEngine { r, c -> FakeSink(r, c).also { sinks += it } }
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        engine.volume = 0.25f
        val ended = CountDownLatch(1)
        engine.onEnded = { ended.countDown() }
        engine.load(t)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        val reference = Pcm.rms(decodeAll(t))
        assertEquals(0.25, Pcm.rms(sinks.single().bytes.toByteArray()) / reference, 0.02)
    }

    @Test
    fun `loading a new track stops the previous one without reporting an end`() {
        val engine = AudioEngine { r, c ->
            val fake = FakeSink(r, c)
            object : PcmSink by fake { override fun write(pcm: ByteArray) { Thread.sleep(6) } }
        }
        var ends = 0
        engine.onEnded = { ends++ }
        val t = Mp4Demuxer.parse(resource("tone440_fragmented.m4a"))
        engine.load(t)
        Thread.sleep(40)
        engine.load(t, startMs = 1_900)
        waitUntil { ends >= 1 }
        Thread.sleep(100)
        assertEquals(1, ends, "only the replacement track ended")
    }
}
