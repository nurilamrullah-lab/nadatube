package com.nadatube.desktop.audio

import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.aac.SampleBuffer

/** Decodes raw AAC frames to interleaved signed 16-bit little-endian PCM. */
class AacDecoder(config: ByteArray) {
    private val decoder = Decoder(config)
    private val buffer = SampleBuffer()

    fun decode(frame: ByteArray): ByteArray {
        decoder.decodeFrame(frame, buffer)
        val data = buffer.data
        if (!buffer.isBigEndian) return data.copyOf()
        val out = ByteArray(data.size)
        var i = 0
        while (i + 1 < data.size) { out[i] = data[i + 1]; out[i + 1] = data[i]; i += 2 }
        return out
    }
}

object Pcm {
    /** Scales 16-bit little-endian samples in place; 1.0 leaves them untouched. */
    fun applyVolume(pcm: ByteArray, volume: Float) {
        if (volume >= 0.999f) return
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val scaled = (s.toShort() * volume).toInt().coerceIn(-32768, 32767)
            pcm[i] = scaled.toByte()
            pcm[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }

    /** Root-mean-square level, 0..1. */
    fun rms(pcm: ByteArray): Double {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
            sum += s * s; n++; i += 2
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n)
    }
}
