package com.nadatube.desktop.audio

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder

/**
 * AAC decoder berbasis JAAD (pure Java, tanpa native library).
 *
 * Dibuat dari AudioSpecificConfig (ASC) yang dibaca demuxer dari box `esds`.
 * Satu [AacDecoder] dipakai untuk satu track; tiap frame mentah (tanpa header ADTS)
 * didekode menjadi PCM 16-bit little-endian.
 */
class AacDecoder(asc: ByteArray) : Closeable {
    private val decoder: Decoder = Decoder.create(asc)
    private val buffer = SampleBuffer().apply { setBigEndian(false) }

    /** Format PCM hasil decode (sample rate bisa 2x lipat ASC bila SBR aktif). */
    val audioFormat: AudioFormat get() = decoder.audioFormat
    val sampleRate: Int get() = audioFormat.sampleRate.toInt()
    val channels: Int get() = audioFormat.channels

    /** Dekode satu frame AAC mentah menjadi PCM 16-bit little-endian (interleaved). */
    fun decodeFrame(frame: ByteArray): ByteArray {
        decoder.decodeFrame(frame, buffer)
        return buffer.data.copyOf()
    }

    override fun close() {}
}
