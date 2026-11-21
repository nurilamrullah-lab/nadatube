package com.nadatube.app.extractor

import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.AudioStream

enum class StreamQuality { BEST, DATA_SAVER }

/**
 * Chooses which audio-only stream to fetch. Only audio streams are ever requested, so no
 * video bytes (and no video ads) are downloaded.
 */
object AudioStreamSelector {
    /** Opus is perceptually more efficient than AAC at equal bitrate. */
    private const val OPUS_EFFICIENCY = 1.3

    fun selectForPlayback(streams: List<AudioStream>, quality: StreamQuality = StreamQuality.BEST): AudioStream? {
        val playable = streams.filter { it.codec != AudioCodec.UNKNOWN }.ifEmpty { streams }
        return when (quality) {
            StreamQuality.BEST -> playable.maxByOrNull(::score)
            StreamQuality.DATA_SAVER -> playable.filter { it.nominalKbps >= 48 }.minByOrNull { it.nominalKbps }
                ?: playable.minByOrNull { it.nominalKbps }
        }
    }

    /** Downloads prefer AAC/M4A so the file can carry embedded tags and cover art. */
    fun selectForDownload(streams: List<AudioStream>): AudioStream? =
        streams.filter { it.codec == AudioCodec.AAC }.maxByOrNull { it.nominalKbps }
            ?: selectForPlayback(streams, StreamQuality.BEST)

    private fun score(s: AudioStream): Double =
        s.nominalKbps * (if (s.codec == AudioCodec.OPUS) OPUS_EFFICIENCY else 1.0) + s.sampleRate / 1_000_000.0

    /**
     * Share of bytes saved by fetching only [audio] instead of a typical muxed 720p stream
     * (~2.5 Mbps video + the same audio).
     */
    fun dataSavingRatio(audio: AudioStream, videoKbps: Int = 2500): Float {
        val a = audio.nominalKbps.toFloat()
        return 1f - a / (a + videoKbps)
    }
}
