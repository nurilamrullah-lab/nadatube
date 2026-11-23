package com.nadatube.app.sponsorblock

import com.nadatube.app.model.SponsorSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Client for https://sponsor.ajay.app (SponsorBlock). */
class SponsorBlockClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://sponsor.ajay.app",
) {
    @Serializable
    private data class Dto(
        val segment: List<Double>,
        val UUID: String,
        val category: String,
        val actionType: String = "skip",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun segments(videoId: String, categories: Set<String> = SponsorSkipLogic.DEFAULT_CATEGORIES): List<SponsorSegment> {
        cache[videoId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/skipSegments".toHttpUrl().newBuilder()
                .addQueryParameter("videoID", videoId)
                .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                when {
                    r.code == 404 -> emptyList()
                    !r.isSuccessful -> throw IOException("SponsorBlock HTTP ${r.code}")
                    else -> parse(r.body?.string().orEmpty())
                }
            }
        }.also { cache[videoId] = it }
    }

    internal fun parse(body: String): List<SponsorSegment> =
        json.decodeFromString<List<Dto>>(body).mapNotNull { d ->
            if (d.segment.size < 2) return@mapNotNull null
            SponsorSegment(
                uuid = d.UUID,
                category = d.category,
                startMs = (d.segment[0] * 1000).toLong(),
                endMs = (d.segment[1] * 1000).toLong(),
                actionType = d.actionType,
            )
        }.sortedBy { it.startMs }
}
