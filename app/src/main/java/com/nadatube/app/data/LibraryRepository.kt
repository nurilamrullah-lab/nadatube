package com.nadatube.app.data

import android.content.Context
import com.nadatube.app.data.db.DownloadEntity
import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.data.db.HistoryEntity
import com.nadatube.app.data.db.NadaDatabase
import com.nadatube.app.data.db.PlaylistEntity
import com.nadatube.app.data.db.PlaylistTrackEntity
import com.nadatube.app.data.db.PlaylistWithCount
import com.nadatube.app.data.db.TrackEntity
import com.nadatube.app.download.DownloadWorker
import com.nadatube.app.download.OfflineTrack
import com.nadatube.app.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** Favorites, history, local playlists and offline downloads, all in Room. */
class LibraryRepository(private val context: Context, private val db: NadaDatabase) {

    suspend fun remember(track: Track) = db.tracks().save(TrackEntity.from(track))

    suspend fun recordPlay(track: Track) {
        remember(track)
        db.tracks().incrementPlayCount(track.videoId)
        db.history().insert(HistoryEntity(videoId = track.videoId, playedAt = System.currentTimeMillis()))
    }

    // favorites
    val favorites: Flow<List<Track>> = db.tracks().observeFavorites().map { list -> list.map { it.toTrack() } }
    val favoriteIds: Flow<Set<String>> = db.tracks().observeFavoriteIds().map { it.toSet() }

    suspend fun setFavorite(track: Track, favorite: Boolean) {
        remember(track)
        db.tracks().setFavorite(track.videoId, favorite, if (favorite) System.currentTimeMillis() else null)
    }

    // history
    val history = db.history().observeHistory()
    val recentlyPlayed: Flow<List<Track>> = db.history().observeRecentDistinct().map { l -> l.map { it.track.toTrack() } }
    suspend fun clearHistory() = db.history().clear()
    suspend fun mostPlayed(limit: Int = 10) = db.tracks().mostPlayed(limit).map { it.toTrack() }

    // playlists
    val playlists: Flow<List<PlaylistWithCount>> = db.playlists().observePlaylists()
    suspend fun createPlaylist(name: String): Long =
        db.playlists().insert(PlaylistEntity(name = name.trim().ifEmpty { "Playlist baru" }, createdAt = System.currentTimeMillis()))

    suspend fun deletePlaylist(id: Long) = db.playlists().delete(id)
    suspend fun playlist(id: Long) = db.playlists().get(id)
    fun playlistTracks(id: Long): Flow<List<Track>> = db.playlists().observeTracks(id).map { l -> l.map { it.toTrack() } }

    suspend fun addToPlaylist(playlistId: Long, track: Track) {
        remember(track)
        val pos = db.playlists().maxPosition(playlistId) + 1
        db.playlists().insertTrack(PlaylistTrackEntity(playlistId, track.videoId, pos, System.currentTimeMillis()))
    }

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) = db.playlists().removeTrack(playlistId, videoId)

    // downloads
    val downloads: Flow<List<OfflineTrack>> = db.downloads().observeAll().map { rows -> rows.mapNotNull(OfflineTrack::from) }

    suspend fun download(track: Track) {
        remember(track)
        val existing = db.downloads().get(track.videoId)
        if (existing?.downloadStatus == DownloadStatus.COMPLETED && existing.filePath?.let { File(it).exists() } == true) return
        db.downloads().upsert(DownloadEntity(track.videoId, createdAt = System.currentTimeMillis()))
        DownloadWorker.enqueue(context, track)
    }

    suspend fun deleteDownload(videoId: String) {
        DownloadWorker.cancel(context, videoId)
        db.downloads().get(videoId)?.let { d ->
            d.filePath?.let { File(it).delete() }
            d.coverPath?.let { File(it).delete() }
        }
        db.downloads().delete(videoId)
    }

    /** Local file for a completed download, if it still exists on disk. */
    suspend fun offlineFile(videoId: String): File? =
        db.downloads().get(videoId)?.takeIf { it.downloadStatus == DownloadStatus.COMPLETED }
            ?.filePath?.let(::File)?.takeIf { it.exists() }

    suspend fun downloadedBytes(): Pair<Long, Int> {
        val done = db.downloads().completed()
        return done.sumOf { it.sizeBytes } to done.size
    }
}
