package com.nadatube.app.extractor

import com.nadatube.app.model.ResolvedStream
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchPage
import com.nadatube.app.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Minimal InnerTube client.
 *
 * - Metadata (search, albums, radio) uses the WEB_REMIX (YouTube Music web) client.
 * - Streams try [STREAM_CLIENTS] in order. A client can answer "OK" yet hand out URLs that
 *   googlevideo rejects with 403 (IOS/ANDROID need a PO token we don't have), so every
 *   candidate's URL is probed before it is accepted.
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://www.youtube.com",
    private val musicBaseUrl: String = "https://music.youtube.com",
    private val streamClients: List<Client> = STREAM_CLIENTS,
) {
    data class Client(val name: String, val id: Int, val version: String, val userAgent: String, val extra: Map<String, Any> = emptyMap())

    companion object {
        val ANDROID_VR = Client(
            "ANDROID_VR", 28, "1.57.29",
            "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
        )
        val ANDROID_VR_NEW = ANDROID_VR.copy(
            version = "1.62.27",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        )
        val ANDROID = Client(
            "ANDROID", 3, "20.10.38",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            mapOf("androidSdkVersion" to 34, "osName" to "Android", "osVersion" to "14"),
        )
        val IOS = Client(
            "IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        )
        val WEB_REMIX = Client("WEB_REMIX", 67, "1.20241127.01.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")

        /**
         * Only clients observed to return direct (unciphered) audio URLs. ANDROID_MUSIC
         * (LOGIN_REQUIRED for every video), ANDROID_TESTSUITE (UNPLAYABLE) and
         * TVHTML5_SIMPLY_EMBEDDED_PLAYER (ERROR) were tried and never yield playable URLs.
         */
        val STREAM_CLIENTS = listOf(ANDROID_VR, ANDROID_VR_NEW, ANDROID, IOS)

        /** Fallback UA for stream requests whose issuing client is unknown. */
        val STREAM_USER_AGENT get() = ANDROID_VR.userAgent

        private val JSON = "application/json".toMediaType()
    }

    var hl: String = "id"
    var gl: String = "ID"

    /** Clients that issued 403 URLs recently for a video; skipped on the next resolve. */
    private val rejected = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Called when a URL from [clientName] failed mid-playback, so re-resolution moves on. */
    fun reportRejected(videoId: String, clientName: String) {
        if (clientName.isNotEmpty()) rejected.getOrPut(videoId) { java.util.concurrent.ConcurrentHashMap.newKeySet() } += clientName
    }

    suspend fun player(videoId: String): ResolvedStream = withContext(Dispatchers.IO) {
        val skip = rejected[videoId].orEmpty()
        val candidates = streamClients.filter { "${it.name}/${it.version}" !in skip }
            .ifEmpty { rejected.remove(videoId); streamClients }
        val failures = mutableListOf<Exception>()
        for (client in candidates) {
            try {
                val body = post(baseUrl, "player", client, buildJsonObject {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                })
                val resolved = InnerTubeParser.parsePlayer(body)
                    .copy(clientName = "${client.name}/${client.version}", userAgent = client.userAgent)
                probe(resolved)
                return@withContext resolved
            } catch (e: Exception) {
                failures += e
            }
        }
        throw playbackFailure(failures)
    }

    /**
     * Ranged GET against the stream we would actually play. URLs issued without a PO token
     * (IOS/ANDROID on gated videos) serve ranges inside roughly the first megabyte and 403
     * everything past it, so probing the start proves nothing. Probe the last byte instead
     * (or an open-ended range when the length is unknown) so only fully playable URLs pass.
     */
    private fun probe(resolved: ResolvedStream) {
        val stream = AudioStreamSelector.selectForPlayback(resolved.streams) ?: return
        val length = stream.contentLength
        val range = if (length != null && length > 0) "bytes=${length - 1}-${length - 1}" else "bytes=0-"
        val request = Request.Builder().url(stream.url)
            .header("User-Agent", resolved.userAgent ?: STREAM_USER_AGENT)
            .header("Range", range)
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw StreamRejectedException(resolved.clientName, r.code)
        }
    }

    private fun playbackFailure(failures: List<Exception>): Exception {
        val blocked = failures.any { (it as? PlayabilityException)?.isBotCheck == true || it is StreamRejectedException }
        return when {
            blocked -> ExtractionException(
                "YouTube memblokir lagu ini dari jaringan saat ini (verifikasi \"bukan bot\"). " +
                    "Coba matikan VPN atau ganti jaringan (Wi-Fi ↔ data seluler).",
            )
            else -> failures.firstOrNull { it is PlayabilityException } ?: failures.lastOrNull()
                ?: ExtractionException("Gagal mengambil stream")
        }
    }

    suspend fun search(query: String, filter: SearchFilter, continuation: String? = null): SearchPage =
        withContext(Dispatchers.IO) {
            val path = if (continuation != null) "search?ctoken=$continuation&continuation=$continuation&type=next" else "search"
            val body = post(musicBaseUrl, path, WEB_REMIX, buildJsonObject {
                if (continuation == null) {
                    put("query", query)
                    put("params", java.net.URLDecoder.decode(filter.params, "UTF-8"))
                }
            })
            InnerTubeParser.parseSearch(body, filter)
        }

    suspend fun browse(browseId: String): List<Track> = withContext(Dispatchers.IO) {
        val body = post(musicBaseUrl, "browse", WEB_REMIX, buildJsonObject { put("browseId", browseId) })
        InnerTubeParser.parseTrackList(body)
    }

    /** Automix "radio" for a seed song, used for the daily mix and autoplay queue. */
    suspend fun radio(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        val body = post(musicBaseUrl, "next", WEB_REMIX, buildJsonObject {
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        })
        InnerTubeParser.parseRadio(body)
    }

    /** Public playlist (from a pasted link) as tracks. */
    suspend fun playlist(playlistId: String): List<Track> =
        browse(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")

    private fun post(base: String, endpoint: String, client: Client, payload: JsonObject): String {
        val body = buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", hl)
                    put("gl", gl)
                    client.extra.forEach { (k, v) ->
                        when (v) {
                            is Int -> put(k, v)
                            else -> put(k, v.toString())
                        }
                    }
                }
            }
        }
        val sep = if ('?' in endpoint) '&' else '?'
        val request = Request.Builder()
            .url("$base/youtubei/v1/$endpoint${sep}prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.id.toString())
            .header("X-YouTube-Client-Version", client.version)
            .header("Origin", base)
            .header("Referer", "$base/")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("InnerTube $endpoint HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Respons kosong")
        }
    }
}
