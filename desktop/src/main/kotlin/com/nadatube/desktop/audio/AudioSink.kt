package com.nadatube.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/**
 * Tujuan PCM hasil decode. Implementasi nyata memakai JavaSound
 * ([SourceDataLine]); test memakai [FakeAudioSink].
 */
interface AudioSink {
    fun open(format: AudioFormat)
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size)
    fun setVolume(volume: Float) {}
    fun drain()
    fun close()
}

class AudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sink JavaSound: membuka line output sistem dan menulis PCM 16-bit. */
class JavaSoundAudioSink : AudioSink {
    private var line: SourceDataLine? = null
    private var volume: Float = 1f

    override fun open(format: AudioFormat) {
        try {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val l = AudioSystem.getLine(info) as SourceDataLine
            l.open(format)
            line = l
            applyVolume()
            l.start()
        } catch (e: LineUnavailableException) {
            throw AudioException("Perangkat audio tidak tersedia: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AudioException("Format audio tidak didukung perangkat: ${e.message}", e)
        }
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int) {
        line?.write(pcm, offset, length)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val l = line ?: return
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = (l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl)
        val db = if (volume <= 0f) gain.minimum else (20 * kotlin.math.log10(volume.toDouble())).toFloat()
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
    }

    override fun drain() {
        line?.drain()
    }

    override fun close() {
        line?.let { it.stop(); it.close() }
        line = null
    }
}

/** Sink test: merekam semua PCM yang ditulis, tanpa menyentuh perangkat audio. */
class FakeAudioSink : AudioSink {
    var format: AudioFormat? = null
        private set
    var bytesWritten: Long = 0
        private set
    var framesWritten: Int = 0
        private set
    var volume: Float = 1f
        private set
    var closed: Boolean = false
        private set

    override fun open(format: AudioFormat) {
        this.format = format
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int) {
        bytesWritten += length
        framesWritten++
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun drain() {}
    override fun close() {
        closed = true
    }
}

/** Sink yang membuang PCM; dipakai mode smoke-test di mesin tanpa audio. */
class NullAudioSink : AudioSink {
    override fun open(format: AudioFormat) {}
    override fun write(pcm: ByteArray, offset: Int, length: Int) {}
    override fun drain() {}
    override fun close() {}
}
