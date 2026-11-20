package com.nadatube.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.nadatube.app.model.Track

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val isFavorite: Boolean = false,
    val favoritedAt: Long? = null,
    val playCount: Int = 0,
) {
    fun toTrack() = Track(videoId, title, artist, album, durationMs, thumbnailUrl)

    companion object {
        fun from(t: Track) = TrackEntity(t.videoId, t.title, t.artist, t.album, t.durationMs, t.thumbnailUrl)
    }
}

@Entity(tableName = "history", indices = [Index("videoId"), Index("playedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val playedAt: Long,
)

data class HistoryWithTrack(
    @Embedded val track: TrackEntity,
    val playedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
    val coverUrl: String?,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("videoId")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val addedAt: Long,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED;

    companion object {
        fun of(value: String): DownloadStatus = entries.firstOrNull { it.name == value } ?: FAILED
    }
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val status: String = DownloadStatus.QUEUED.name,
    val progress: Float = 0f,
    val filePath: String? = null,
    val coverPath: String? = null,
    val mimeType: String? = null,
    val bitrateKbps: Int = 0,
    val sizeBytes: Long = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    val error: String? = null,
) {
    val downloadStatus: DownloadStatus get() = DownloadStatus.of(status)
}

data class DownloadWithTrack(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "videoId", entityColumn = "videoId")
    val track: TrackEntity?,
)
