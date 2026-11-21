package com.nadatube.app.lyrics

import com.nadatube.app.model.Lyrics
import com.nadatube.app.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Synced lyrics from LRCLIB (https://lrclib.net), a free open lyrics database. */
class LyricsClient(private val http: OkHttpClient, private val baseUrl: String = "https://lrclib.net") {
    @Serializable
    private data class Dto(val syncedLyrics: String? = null, val plainLyrics: String? = null, val duration: Double? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Lyrics>()

    suspend fun lyrics(track: Track): Lyrics? {
        cache[track.videoId]?.let { return it }
        val title = TitleCleaner.clean(track.title, track.artist)
        return withContext(Dispatchers.IO) {
            val exact = runCatching {
                get(
                    "$baseUrl/api/get".toHttpUrl().newBuilder()
                        .addQueryParameter("track_name", title)
                        .addQueryParameter("artist_name", track.artist)
                        .apply { if (track.durationMs > 0) addQueryParameter("duration", (track.durationMs / 1000).toString()) }
                        .build().toString(),
                )?.let { listOf(json.decodeFromString<Dto>(it)) }
            }.getOrNull()
            val candidates = exact ?: runCatching {
                get(
                    "$baseUrl/api/search".toHttpUrl().newBuilder()
                        .addQueryParameter("q", "$title ${track.artist}").build().toString(),
                )?.let { json.decodeFromString<List<Dto>>(it) }
            }.getOrNull().orEmpty()
            val best = candidates.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: candidates.firstOrNull()
            best?.let { Lyrics(LrcParser.parse(it.syncedLyrics.orEmpty()), it.plainLyrics) }
        }?.also { cache[track.videoId] = it }
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "NadaTube/1.0 (https://github.com/nurilamrullah-lab/nadatube)").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
}
