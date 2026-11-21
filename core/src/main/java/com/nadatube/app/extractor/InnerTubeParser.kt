package com.nadatube.app.extractor

import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.AudioStream
import com.nadatube.app.model.ResolvedStream
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.model.SearchPage
import com.nadatube.app.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ExtractionException(message: String) : Exception(message)

/** InnerTube refused playback (e.g. LOGIN_REQUIRED "confirm you're not a bot"). */
class PlayabilityException(val status: String, val reason: String) : Exception("Video tidak dapat diputar: $reason") {
    val isBotCheck: Boolean get() = status == "LOGIN_REQUIRED" && ("bot" in reason.lowercase())
}

/**
 * Pure parsers for InnerTube responses. No networking here so everything is unit-testable
 * against recorded fixtures.
 */
object InnerTubeParser {
    private val json = Json { ignoreUnknownKeys = true }

    private const val PAGE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val PAGE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private val DURATION = Regex("""^\d{1,2}([:.]\d{2}){1,2}$""")
    private val TYPE_LABELS = setOf("Song", "Video", "Lagu", "Album", "Single", "EP", "Artist", "Artis", "Playlist", "Episode")

    fun parseRoot(body: String): JsonElement = json.parseToJsonElement(body)

    // ---------------------------------------------------------------- player

    fun parsePlayer(body: String, nowMs: Long = System.currentTimeMillis()): ResolvedStream {
        val root = parseRoot(body)
        val status = root.path("playabilityStatus", "status").str
        if (status != "OK") {
            val reason = root.path("playabilityStatus", "reason").str ?: status ?: "unknown"
            throw PlayabilityException(status ?: "UNKNOWN", reason)
        }
        val details = root["videoDetails"]
        val videoId = details["videoId"].str ?: throw ExtractionException("videoId hilang")
        val track = Track(
            videoId = videoId,
            title = details["title"].str.orEmpty(),
            artist = details["author"].str.orEmpty().removeSuffix(" - Topic"),
            durationMs = (details["lengthSeconds"].long ?: 0L) * 1000L,
            thumbnailUrl = bestThumbnail(details.path("thumbnail", "thumbnails")),
        )
        val streaming = root["streamingData"]
        val streams = streaming["adaptiveFormats"].arr.mapNotNull(::parseAudioFormat)
        if (streams.isEmpty()) throw ExtractionException("Tidak ada stream audio langsung untuk $videoId")
        val expiresIn = streaming["expiresInSeconds"].long ?: 6 * 3600L
        // The URL's own `expire` is what googlevideo enforces; trust it over expiresInSeconds.
        val urlExpiry = streams.mapNotNull { urlExpiryMs(it.url) }.minOrNull()
        val expiresAt = listOfNotNull(nowMs + expiresIn * 1000L, urlExpiry).min()
        val loudness = root.path("playerConfig", "audioConfig", "loudnessDb").str?.toFloatOrNull()
        return ResolvedStream(track, streams, loudness, expiresAt)
    }

    /** `responseContext.visitorData` from a `visitor_id` (or any InnerTube) response. */
    fun parseVisitorData(body: String): String? =
        parseRoot(body).path("responseContext", "visitorData").str?.takeIf { it.isNotBlank() }

    /** `expire` query parameter (unix seconds) of a googlevideo URL, in ms. */
    fun urlExpiryMs(url: String): Long? =
        Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    /** Returns null for video formats and for ciphered formats (we never decipher signatures). */
    internal fun parseAudioFormat(f: JsonElement): AudioStream? {
        val mime = f["mimeType"].str ?: return null
        if (!mime.startsWith("audio/")) return null
        val url = f["url"].str ?: return null
        val codec = when {
            "opus" in mime -> AudioCodec.OPUS
            "mp4a" in mime -> AudioCodec.AAC
            else -> AudioCodec.UNKNOWN
        }
        return AudioStream(
            itag = f["itag"].int ?: return null,
            url = url,
            mimeType = mime.substringBefore(';').trim(),
            codec = codec,
            bitrate = f["bitrate"].int ?: 0,
            averageBitrate = f["averageBitrate"].int,
            sampleRate = f["audioSampleRate"].int ?: 44_100,
            channels = f["audioChannels"].int ?: 2,
            contentLength = f["contentLength"].long,
        )
    }

    // ---------------------------------------------------------------- search

    fun parseSearch(body: String, filter: SearchFilter): SearchPage {
        val root = parseRoot(body)
        val shelves = root.collect("musicShelfRenderer") + root.collect("musicShelfContinuation")
        val items = shelves.flatMap { shelf ->
            shelf["contents"].arr.mapNotNull { it["musicResponsiveListItemRenderer"] }
        }.mapNotNull { parseListItem(it, filter) }
        val continuation = shelves.firstNotNullOfOrNull {
            it["continuations"][0].path("nextContinuationData", "continuation").str
        }
        return SearchPage(items.distinctBy { it.id }, continuation)
    }

    internal fun parseListItem(item: JsonElement, filter: SearchFilter): SearchItem? {
        val columns = item["flexColumns"].arr.map { it.path("musicResponsiveListItemFlexColumnRenderer", "text") }
        val title = columns.getOrNull(0).text() ?: return null
        val runs = columns.getOrNull(1)["runs"].arr
        val thumb = bestThumbnail(item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val browse = item.path("navigationEndpoint", "browseEndpoint")
        return when (filter) {
            SearchFilter.SONGS -> parseSongItem(item, columns, thumb)?.let { SearchItem.Song(it) }
            SearchFilter.ARTISTS -> SearchItem.Artist(
                id = browse["browseId"].str ?: return null,
                name = title,
                subtitle = runs.mapNotNull { it["text"].str }.filterNot { it.trim() == "•" || it in TYPE_LABELS }
                    .joinToString("").trim().removePrefix("•").trim(),
                thumbnailUrl = thumb,
            )
            SearchFilter.ALBUMS -> {
                val texts = runs.mapNotNull { it["text"].str }.filter { it.trim() != "•" }
                SearchItem.Album(
                    id = browse["browseId"].str ?: return null,
                    title = title,
                    artist = artistNames(runs) ?: texts.getOrNull(1).orEmpty(),
                    year = texts.lastOrNull()?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                    thumbnailUrl = thumb,
                )
            }
            SearchFilter.PLAYLISTS -> SearchItem.Playlist(
                id = browse["browseId"].str ?: return null,
                title = title,
                author = runs.firstOrNull()?.get("text").str?.trim().orEmpty(),
                thumbnailUrl = thumb,
            )
        }
    }

    private fun parseSongItem(item: JsonElement, columns: List<JsonElement?>, fallbackThumb: String?): Track? {
        val titleRun = columns.getOrNull(0)["runs"][0]
        val videoId = item.path("playlistItemData", "videoId").str
            ?: titleRun.path("navigationEndpoint", "watchEndpoint", "videoId").str
            ?: return null
        val runs = columns.getOrNull(1)["runs"].arr
        val texts = runs.mapNotNull { it["text"].str?.trim() }.filter { it.isNotEmpty() && it != "•" && it != "&" && it != "," }
        val fixedDuration = item["fixedColumns"].arr.firstNotNullOfOrNull {
            it.path("musicResponsiveListItemFixedColumnRenderer", "text").text()
        }
        val duration = (texts.lastOrNull { DURATION.matches(it) } ?: fixedDuration)?.let(::parseDuration) ?: 0L
        val artist = artistNames(runs)
            ?: texts.firstOrNull { it !in TYPE_LABELS && !DURATION.matches(it) }
            ?: ""
        val album = runs.firstOrNull { pageType(it) == PAGE_ALBUM }?.get("text").str
        return Track(
            videoId = videoId,
            title = titleRun["text"].str ?: return null,
            artist = artist,
            album = album,
            durationMs = duration,
            thumbnailUrl = fallbackThumb,
        )
    }

    // ---------------------------------------------------------------- browse / next

    /** Album or playlist page: every list item carrying a videoId becomes a track. */
    fun parseTrackList(body: String): List<Track> {
        val root = parseRoot(body)
        val headerThumb = root.collect("croppedSquareThumbnailRenderer").firstOrNull()
            ?.let { bestThumbnail(it.path("thumbnail", "thumbnails")) }
            ?: root.collect("musicThumbnailRenderer").firstOrNull()?.let { bestThumbnail(it.path("thumbnail", "thumbnails")) }
        val albumTitle = root.collect("musicResponsiveHeaderRenderer").firstOrNull()?.get("title").text()
        val albumArtist = root.collect("musicResponsiveHeaderRenderer").firstOrNull()?.get("straplineTextOne").text()
        return root.collect("musicResponsiveListItemRenderer").mapNotNull { item ->
            val columns = item["flexColumns"].arr.map { it.path("musicResponsiveListItemFlexColumnRenderer", "text") }
            parseSongItem(item, columns, null)?.let { t ->
                t.copy(
                    thumbnailUrl = bestThumbnail(item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")) ?: headerThumb,
                    artist = t.artist.ifEmpty { albumArtist.orEmpty() },
                    album = t.album ?: albumTitle,
                )
            }
        }.distinctBy { it.videoId }
    }

    /** "next" endpoint with an RDAMVM radio playlist: the automix queue for a seed song. */
    fun parseRadio(body: String): List<Track> =
        parseRoot(body).collect("playlistPanelVideoRenderer").mapNotNull { v ->
            val videoId = v["videoId"].str ?: return@mapNotNull null
            val byline = v["longBylineText"]["runs"].arr
            Track(
                videoId = videoId,
                title = v["title"].text() ?: return@mapNotNull null,
                artist = artistNames(byline) ?: byline.firstOrNull()?.get("text").str.orEmpty(),
                album = byline.firstOrNull { pageType(it) == PAGE_ALBUM }?.get("text").str,
                durationMs = v["lengthText"].text()?.let(::parseDuration) ?: 0L,
                thumbnailUrl = bestThumbnail(v.path("thumbnail", "thumbnails")),
            )
        }.distinctBy { it.videoId }

    // ---------------------------------------------------------------- helpers

    private fun pageType(run: JsonElement): String? = run.path(
        "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
        "browseEndpointContextMusicConfig", "pageType",
    ).str

    private fun artistNames(runs: List<JsonElement>): String? =
        runs.filter { pageType(it) == PAGE_ARTIST }.mapNotNull { it["text"].str }.joinToString(", ").ifEmpty { null }

    /** "4:27" → 267000, "1:02:03" → 3723000; hl=id uses dots ("4.27"). */
    fun parseDuration(text: String): Long? {
        if (!DURATION.matches(text.trim())) return null
        val parts = text.trim().split(':', '.').map { it.toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000L
    }

    /** Picks the largest thumbnail and asks Google's image CDN for a 544px square cover. */
    fun bestThumbnail(thumbs: JsonElement?): String? {
        val best = thumbs.arr.maxByOrNull { it["width"].int ?: 0 } ?: return null
        val url = best["url"].str ?: return null
        return upscaleThumbnail(url)
    }

    fun upscaleThumbnail(url: String, size: Int = 544): String =
        if ("googleusercontent.com" in url || "ggpht.com" in url) {
            url.replace(Regex("""=w\d+-h\d+.*$"""), "=w$size-h$size-l90-rj")
                .replace(Regex("""=s\d+.*$"""), "=s$size")
        } else {
            url
        }
}
