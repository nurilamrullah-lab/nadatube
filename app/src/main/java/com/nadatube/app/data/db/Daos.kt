package com.nadatube.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: TrackEntity): Long

    @Query("UPDATE tracks SET title = :title, artist = :artist, album = COALESCE(:album, album), durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END, thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl) WHERE videoId = :videoId")
    suspend fun updateMetadata(videoId: String, title: String, artist: String, album: String?, durationMs: Long, thumbnailUrl: String?)

    /** Insert or refresh metadata without clobbering favorite flags / play counts. */
    @Transaction
    suspend fun save(track: TrackEntity) {
        if (insertIgnore(track) == -1L) {
            updateMetadata(track.videoId, track.title, track.artist, track.album, track.durationMs, track.thumbnailUrl)
        }
    }

    @Query("SELECT * FROM tracks WHERE videoId = :videoId")
    suspend fun get(videoId: String): TrackEntity?

    @Query("UPDATE tracks SET isFavorite = :favorite, favoritedAt = :at WHERE videoId = :videoId")
    suspend fun setFavorite(videoId: String, favorite: Boolean, at: Long?)

    @Query("UPDATE tracks SET playCount = playCount + 1 WHERE videoId = :videoId")
    suspend fun incrementPlayCount(videoId: String)

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT videoId FROM tracks WHERE isFavorite = 1")
    fun observeFavoriteIds(): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    suspend fun mostPlayed(limit: Int): List<TrackEntity>
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query(
        """SELECT t.*, h.playedAt AS playedAt FROM history h JOIN tracks t ON t.videoId = h.videoId
           ORDER BY h.playedAt DESC LIMIT :limit""",
    )
    fun observeHistory(limit: Int = 200): Flow<List<HistoryWithTrack>>

    /** Each track once, most recent first (for the "recently played" carousel). */
    @Query(
        """SELECT t.*, MAX(h.playedAt) AS playedAt FROM history h JOIN tracks t ON t.videoId = h.videoId
           GROUP BY h.videoId ORDER BY playedAt DESC LIMIT :limit""",
    )
    fun observeRecentDistinct(limit: Int = 20): Flow<List<HistoryWithTrack>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        """SELECT p.*,
             (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS trackCount,
             (SELECT t.thumbnailUrl FROM playlist_tracks pt JOIN tracks t ON t.videoId = pt.videoId
                WHERE pt.playlistId = p.id ORDER BY pt.position LIMIT 1) AS coverUrl
           FROM playlists p ORDER BY p.createdAt DESC""",
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query(
        """SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.videoId = pt.videoId
           WHERE pt.playlistId = :playlistId ORDER BY pt.position""",
    )
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(entry: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeTrack(playlistId: Long, videoId: String)
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun get(videoId: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE videoId = :videoId")
    suspend fun updateProgress(videoId: String, status: String, progress: Float)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadWithTrack>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED'")
    suspend fun completed(): List<DownloadEntity>
}
