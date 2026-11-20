package com.nadatube.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nadatube.app.model.Track

object MediaItems {
    const val SCHEME = "nadatube"
    private const val EXTRA_DURATION = "durationMs"

    /** Streams use a placeholder URI resolved lazily (and re-resolved after expiry) at load time. */
    fun streamUri(videoId: String): Uri = Uri.parse("$SCHEME://track/$videoId")

    fun from(track: Track, localFile: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(localFile?.let { Uri.fromFile(java.io.File(it)) } ?: streamUri(track.videoId))
            .setCustomCacheKey(track.videoId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.thumbnailUrl?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply { putLong(EXTRA_DURATION, track.durationMs) })
                    .build(),
            )
            .build()

    fun toTrack(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            videoId = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString(),
            durationMs = m.extras?.getLong(EXTRA_DURATION) ?: 0L,
            thumbnailUrl = m.artworkUri?.toString(),
        )
    }
}
