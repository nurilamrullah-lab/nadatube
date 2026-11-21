package com.nadatube.desktop.data

import com.nadatube.app.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
) {
    fun toTrack() = Track(videoId, title, artist, album, durationMs, thumbnailUrl)

    companion object {
        fun of(t: Track) = StoredTrack(t.videoId, t.title, t.artist, t.album, t.durationMs, t.thumbnailUrl)
    }
}

@Serializable
data class StoredPlay(val track: StoredTrack, val playedAt: Long)

@Serializable
data class StoredState(
    val themeMode: String = "SYSTEM",
    val volume: Float = 1f,
    val sponsorBlock: Boolean = true,
    val visitorData: String? = null,
    val favorites: List<StoredTrack> = emptyList(),
    val history: List<StoredPlay> = emptyList(),
)

object AppPaths {
    /** `%APPDATA%\NadaTube` on Windows, `~/.nadatube` elsewhere. */
    fun dataDir(): File {
        val appData = System.getenv("APPDATA")
        return if (appData != null) File(appData, "NadaTube") else File(System.getProperty("user.home"), ".nadatube")
    }
}

/** Preferences, favourites and history in one JSON file, written atomically. */
class Store(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<StoredState> = _state.asStateFlow()

    private fun load(): StoredState {
        if (!file.exists()) return StoredState()
        return try {
            json.decodeFromString(StoredState.serializer(), file.readText())
        } catch (_: Exception) {
            // Keep the unreadable file for inspection and start clean instead of crashing on launch.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            StoredState()
        }
    }

    @Synchronized
    fun update(transform: (StoredState) -> StoredState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StoredState.serializer(), next))
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    fun isFavorite(id: String) = _state.value.favorites.any { it.videoId == id }

    fun toggleFavorite(track: Track) = update { s ->
        if (s.favorites.any { it.videoId == track.videoId }) s.copy(favorites = s.favorites.filterNot { it.videoId == track.videoId })
        else s.copy(favorites = listOf(StoredTrack.of(track)) + s.favorites)
    }

    /** Newest first; replaying the same song right away does not stack duplicates. */
    fun recordPlay(track: Track) = update { s ->
        val rest = if (s.history.firstOrNull()?.track?.videoId == track.videoId) s.history.drop(1) else s.history
        s.copy(history = (listOf(StoredPlay(StoredTrack.of(track), clock())) + rest).take(MAX_HISTORY))
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    companion object {
        const val MAX_HISTORY = 300
    }
}
