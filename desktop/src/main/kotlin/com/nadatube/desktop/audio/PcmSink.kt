package com.nadatube.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** Where decoded PCM goes. The real one is JavaSound; tests use an in-memory fake. */
interface PcmSink : AutoCloseable {
    /** Blocks until the data is queued, which paces playback to real time. */
    fun write(pcm: ByteArray)

    /** Milliseconds of audio actually played since the sink opened. */
    fun playedMs(): Long
    fun pause()
    fun resume()
}

class JavaSoundSink(sampleRate: Int, channels: Int) : PcmSink {
    private val line: SourceDataLine
    private val frameBytes = channels * 2
    private val rate = sampleRate

    init {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        line = AudioSystem.getLine(info) as SourceDataLine
        // ~250 ms: small enough that pause/seek feel immediate, big enough to survive GC pauses.
        line.open(format, (sampleRate * frameBytes * 0.25).toInt().coerceAtLeast(4096))
        line.start()
    }

    override fun write(pcm: ByteArray) {
        var off = 0
        while (off < pcm.size) off += line.write(pcm, off, pcm.size - off)
    }

    override fun playedMs(): Long = line.longFramePosition * 1000L / rate
    override fun pause() = line.stop()
    override fun resume() = line.start()

    override fun close() {
        runCatching { line.stop(); line.flush(); line.close() }
    }

    companion object {
        fun available(): Boolean = runCatching { AudioSystem.getMixerInfo().isNotEmpty() }.getOrDefault(false)
    }
}
