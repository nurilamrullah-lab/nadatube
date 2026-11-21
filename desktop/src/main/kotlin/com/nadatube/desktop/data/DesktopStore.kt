package com.nadatube.desktop.data

import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DesktopSettings(
    val themeMode: String = ThemeMode.SYSTEM.name,
    val quality: String = StreamQuality.BEST.name,
    val volume: Float = 0.8f,
    val visitorData: String? = null,
    val downloadDir: String? = null,
)

@Serializable
data class HistoryEntry(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val playedAtMs: Long = 0L,
    val filePath: String? = null,
) {
    fun toTrack(): Track = Track(videoId, title, artist, null, durationMs, thumbnailUrl)
}

@Serializable
data class DesktopLibrary(
    val history: List<HistoryEntry> = emptyList(),
    val likedIds: List<String> = emptyList(),
    val downloads: List<HistoryEntry> = emptyList(),
)

/**
 * Pengaturan + riwayat + unduhan desktop, disimpan sebagai JSON di direktori config:
 * Windows `%APPDATA%\NadaTube`, Linux/macOS `~/.config/nadatube`.
 */
class DesktopStore(val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val settingsFile = File(dir, "settings.json")
    private val libraryFile = File(dir, "library.json")

    @Volatile private var settings: DesktopSettings = loadSettings()
    @Volatile private var library: DesktopLibrary = loadLibrary()

    fun cacheDir(): File = File(dir, "cache")

    fun downloadDir(): File =
        settings.downloadDir?.let { File(it) }
            ?: File(System.getProperty("user.home"), "Music${File.separator}NadaTube")

    fun setDownloadDir(path: String?) {
        settings = settings.copy(downloadDir = path)
        saveSettings()
    }

    fun themeMode(): ThemeMode = ThemeMode.of(settings.themeMode)
    fun setThemeMode(mode: ThemeMode) {
        settings = settings.copy(themeMode = mode.name)
        saveSettings()
    }

    fun quality(): StreamQuality =
        runCatching { StreamQuality.valueOf(settings.quality) }.getOrDefault(StreamQuality.BEST)

    fun setQuality(quality: StreamQuality) {
        settings = settings.copy(quality = quality.name)
        saveSettings()
    }

    fun volume(): Float = settings.volume.coerceIn(0f, 1f)
    fun setVolume(volume: Float) {
        settings = settings.copy(volume = volume.coerceIn(0f, 1f))
        saveSettings()
    }

    fun visitorData(): String? = settings.visitorData
    fun saveVisitorData(visitorData: String) {
        if (settings.visitorData != visitorData) {
            settings = settings.copy(visitorData = visitorData)
            saveSettings()
        }
    }

    fun history(): List<HistoryEntry> = library.history
    fun likedIds(): List<String> = library.likedIds
    fun downloads(): List<HistoryEntry> = library.downloads

    fun recordHistory(track: Track) {
        val entry = HistoryEntry(track.videoId, track.title, track.artist, track.thumbnailUrl, track.durationMs, System.currentTimeMillis())
        val rest = library.history.filterNot { it.videoId == track.videoId }
        library = library.copy(history = (listOf(entry) + rest).take(MAX_HISTORY))
        saveLibrary()
    }

    fun isLiked(videoId: String): Boolean = videoId in library.likedIds

    fun toggleLike(videoId: String): Boolean {
        val liked = if (videoId in library.likedIds) {
            library.likedIds - videoId
        } else {
            library.likedIds + videoId
        }
        library = library.copy(likedIds = liked)
        saveLibrary()
        return videoId in liked
    }

    fun recordDownload(track: Track, file: File) {
        val entry = HistoryEntry(track.videoId, track.title, track.artist, track.thumbnailUrl, track.durationMs, System.currentTimeMillis(), file.absolutePath)
        val rest = library.downloads.filterNot { it.videoId == track.videoId }
        library = library.copy(downloads = listOf(entry) + rest)
        saveLibrary()
    }

    private fun loadSettings(): DesktopSettings = runCatching {
        if (settingsFile.exists()) json.decodeFromString<DesktopSettings>(settingsFile.readText()) else DesktopSettings()
    }.getOrDefault(DesktopSettings())

    private fun loadLibrary(): DesktopLibrary = runCatching {
        if (libraryFile.exists()) json.decodeFromString<DesktopLibrary>(libraryFile.readText()) else DesktopLibrary()
    }.getOrDefault(DesktopLibrary())

    @Synchronized
    private fun saveSettings() {
        dir.mkdirs()
        settingsFile.writeText(json.encodeToString(settings))
    }

    @Synchronized
    private fun saveLibrary() {
        dir.mkdirs()
        libraryFile.writeText(json.encodeToString(library))
    }

    companion object {
        const val MAX_HISTORY = 200

        fun defaultConfigDir(): File {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                File(appData, "NadaTube")
            } else {
                File(System.getProperty("user.home"), ".config${File.separator}nadatube")
            }
        }
    }
}
