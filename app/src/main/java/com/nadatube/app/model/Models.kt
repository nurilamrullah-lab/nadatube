package com.nadatube.app.model

/** A playable YouTube track, independent of where it came from (search, radio, library). */
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
)

sealed interface SearchItem {
    val id: String

    data class Song(val track: Track) : SearchItem {
        override val id: String get() = track.videoId
    }

    data class Artist(override val id: String, val name: String, val subtitle: String, val thumbnailUrl: String?) : SearchItem

    data class Album(override val id: String, val title: String, val artist: String, val year: String?, val thumbnailUrl: String?) : SearchItem

    data class Playlist(override val id: String, val title: String, val author: String, val thumbnailUrl: String?) : SearchItem
}

data class SearchPage(val items: List<SearchItem>, val continuation: String?)

enum class SearchFilter(val label: String, val params: String) {
    SONGS("Lagu", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUMS("Album", "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTISTS("Artis", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLISTS("Playlist", "EgeKAQQoAEABagoQAxAEEAoQCRAF"),
}

enum class AudioCodec(val label: String) { OPUS("Opus"), AAC("AAC"), UNKNOWN("Audio") }

data class AudioStream(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codec: AudioCodec,
    val bitrate: Int,
    val averageBitrate: Int?,
    val sampleRate: Int,
    val channels: Int,
    val contentLength: Long?,
) {
    /** File extension used when this stream is stored offline. */
    val extension: String get() = if (mimeType.startsWith("audio/mp4")) "m4a" else "webm"

    /** Nominal YouTube tier in kbps (the advertised bitrate, not the instantaneous peak). */
    val nominalKbps: Int get() = NOMINAL_KBPS[itag] ?: ((averageBitrate ?: bitrate) / 1000)

    val isHighQuality: Boolean get() = nominalKbps >= 160 || (codec == AudioCodec.AAC && nominalKbps >= 256)

    val qualityLabel: String get() = "$nominalKbps kbps ${codec.label}"

    val sampleRateLabel: String get() = "%.1f kHz".format(sampleRate / 1000f).replace(".0 ", " ")

    companion object {
        val NOMINAL_KBPS = mapOf(
            139 to 48, 140 to 128, 141 to 256,
            249 to 50, 250 to 70, 251 to 160,
            774 to 256,
        )
    }
}

data class ResolvedStream(
    val track: Track,
    val streams: List<AudioStream>,
    val loudnessDb: Float?,
    val expiresAtMs: Long,
)

data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
    val actionType: String = "skip",
) {
    val lengthMs: Long get() = endMs - startMs
}

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val plain: String?) {
    val isSynced: Boolean get() = lines.isNotEmpty()
}
