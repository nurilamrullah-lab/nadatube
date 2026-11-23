package com.nadatube.app.playback

import android.content.Context
import androidx.media3.datasource.AesCipherDataSink
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.extractor.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Media3 data-source chain for playback:
 * file:// (downloads) → read directly;
 * nadatube://track/<id> → AES-encrypted chunk cache → resolve a fresh googlevideo URL → OkHttp.
 */
object StreamDataSources {

    /**
     * HTTP leg. No factory-level User-Agent: OkHttpDataSource *adds* that header on top of
     * per-request headers, which would send two UAs. Instead each request carries the UA of
     * the client that issued its URL; the default only covers unknown URLs.
     */
    fun http(httpClient: OkHttpClient): HttpDataSource.Factory =
        OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(mapOf("User-Agent" to InnerTubeClient.STREAM_USER_AGENT))

    fun resolving(upstream: DataSource.Factory, resolver: StreamResolver): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(upstream, object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                if (dataSpec.uri.scheme != MediaItems.SCHEME) return dataSpec
                val videoId = dataSpec.uri.lastPathSegment ?: throw IOException("URI tidak valid")
                val stream = try {
                    runBlocking { resolver.resolvePlayback(videoId) }
                } catch (e: Exception) {
                    throw IOException(e.message, e)
                }
                return dataSpec.withUri(android.net.Uri.parse(stream.url))
                    .withAdditionalHeaders(mapOf("User-Agent" to resolver.userAgent(videoId)))
            }
        })

    /** Bytes of different formats must never share a cache entry, so the quality is part of the key. */
    fun cacheKeyFactory(quality: () -> StreamQuality) = CacheKeyFactory { spec ->
        val base = spec.key ?: spec.uri.toString()
        if (spec.uri.scheme == MediaItems.SCHEME) "$base@${quality().name}" else base
    }

    fun create(
        context: Context,
        httpClient: OkHttpClient,
        resolver: StreamResolver,
        cache: Cache,
        cacheKey: ByteArray,
        quality: () -> StreamQuality,
    ): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeyFactory(quality))
            .setUpstreamDataSourceFactory(resolving(http(httpClient), resolver))
            .setCacheWriteDataSinkFactory {
                AesCipherDataSink(cacheKey, CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE), ByteArray(32 * 1024))
            }
            .setCacheReadDataSourceFactory { AesCipherDataSource(cacheKey, FileDataSource()) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(context, cached)
    }
}

/** What the service should do after a playback error; pure so it can be unit-tested. */
object PlaybackRecovery {
    const val MAX_RETRIES = 2

    enum class Action { REJECT_AND_RETRY, RETRY, GIVE_UP }

    /** googlevideo answers 403 for unusable/IP-mismatched URLs and 410 once a URL has expired. */
    fun decide(httpCode: Int?, attemptsSoFar: Int, extractionFailed: Boolean = false): Action = when {
        // The extractor already walked every client; repeating that won't change the answer.
        extractionFailed -> Action.GIVE_UP
        attemptsSoFar >= MAX_RETRIES -> Action.GIVE_UP
        httpCode == 403 || httpCode == 410 || httpCode == 404 -> Action.REJECT_AND_RETRY
        httpCode == null -> Action.RETRY
        httpCode in 500..599 -> Action.RETRY
        else -> Action.GIVE_UP
    }

    fun isExtractionFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is com.nadatube.app.extractor.ExtractionException || it is com.nadatube.app.extractor.PlayabilityException
        }

    /** HTTP status from anywhere in a PlaybackException's cause chain, if any. */
    fun httpCode(error: Throwable): Int? =
        generateSequence(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
}
