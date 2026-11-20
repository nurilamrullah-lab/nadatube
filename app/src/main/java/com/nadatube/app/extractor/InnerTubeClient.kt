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
 * - Streams use the ANDROID_VR client, which currently returns direct, unciphered
 *   googlevideo URLs for audio-only adaptive formats.
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://www.youtube.com",
    private val musicBaseUrl: String = "https://music.youtube.com",
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
        val IOS = Client(
            "IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        )
        val WEB_REMIX = Client("WEB_REMIX", 67, "1.20241127.01.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")

        /** googlevideo checks that the downloader's UA matches the client that requested the URL. */
        val STREAM_USER_AGENT get() = ANDROID_VR.userAgent

        private val JSON = "application/json".toMediaType()
    }

    var hl: String = "id"
    var gl: String = "ID"

    suspend fun player(videoId: String): ResolvedStream = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (client in listOf(ANDROID_VR, ANDROID_VR_NEW, IOS)) {
            try {
                val body = post(baseUrl, "player", client, buildJsonObject {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                })
                return@withContext InnerTubeParser.parsePlayer(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: ExtractionException("Gagal mengambil stream")
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
