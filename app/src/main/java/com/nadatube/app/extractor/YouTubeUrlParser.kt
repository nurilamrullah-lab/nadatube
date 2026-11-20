package com.nadatube.app.extractor

import java.net.URI

sealed interface YouTubeLink {
    data class Video(val videoId: String) : YouTubeLink
    data class Playlist(val playlistId: String, val videoId: String? = null) : YouTubeLink
}

/** Recognises pasted YouTube / YouTube Music links (and bare video IDs). */
object YouTubeUrlParser {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "m.youtube.com", "www.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")

    fun parse(input: String): YouTubeLink? {
        val text = input.trim()
        if (VIDEO_ID.matches(text)) return YouTubeLink.Video(text)
        val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val query = parseQuery(uri.rawQuery)
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

        val videoId = when {
            host.endsWith("youtu.be") -> segments.firstOrNull()
            segments.firstOrNull() in setOf("shorts", "embed", "live", "v") -> segments.getOrNull(1)
            else -> query["v"]
        }?.takeIf { VIDEO_ID.matches(it) }

        val list = query["list"]?.takeIf { it.isNotBlank() }
        return when {
            list != null && (videoId == null || segments.firstOrNull() == "playlist") -> YouTubeLink.Playlist(list, videoId)
            videoId != null -> YouTubeLink.Video(videoId)
            list != null -> YouTubeLink.Playlist(list)
            else -> null
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
}
