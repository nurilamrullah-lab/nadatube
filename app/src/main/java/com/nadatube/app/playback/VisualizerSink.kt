package com.nadatube.app.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Taps decoded PCM inside ExoPlayer's audio pipeline and publishes a rolling window of RMS
 * levels for the waveform visualizer. Unlike android.media.audiofx.Visualizer this needs no
 * RECORD_AUDIO permission.
 */
class VisualizerSink : TeeAudioProcessor.AudioBufferSink {
    private val _levels = MutableStateFlow(FloatArray(BARS))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    private var encoding = C.ENCODING_PCM_16BIT
    private val window = FloatArray(BARS)
    private var lastEmitNs = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val now = System.nanoTime()
        if (now - lastEmitNs < EMIT_INTERVAL_NS) return
        lastEmitNs = now
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val rms = rms(b, encoding)
        System.arraycopy(window, 1, window, 0, BARS - 1)
        window[BARS - 1] = rms
        _levels.value = window.copyOf()
    }

    fun reset() {
        window.fill(0f)
        _levels.value = window.copyOf()
    }

    companion object {
        const val BARS = 48
        private const val EMIT_INTERVAL_NS = 40_000_000L

        /** RMS in 0..1 of a PCM buffer (16-bit or float), lightly compressed for display. */
        fun rms(buffer: ByteBuffer, encoding: Int): Float {
            var sum = 0.0
            var n = 0
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = buffer.asFloatBuffer()
                while (fb.hasRemaining()) {
                    val v = fb.get()
                    sum += v * v
                    n++
                }
            } else {
                val sb = buffer.asShortBuffer()
                while (sb.hasRemaining()) {
                    val v = sb.get() / 32768f
                    sum += v * v
                    n++
                }
            }
            if (n == 0) return 0f
            return sqrt(sqrt(sum / n)).toFloat().coerceIn(0f, 1f)
        }
    }
}
