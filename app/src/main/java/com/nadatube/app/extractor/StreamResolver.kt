package com.nadatube.app.extractor

import com.nadatube.app.model.AudioStream
import com.nadatube.app.model.ResolvedStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Resolves and caches stream URLs until shortly before googlevideo expires them. */
class StreamResolver(
    private val client: InnerTubeClient,
    private val qualityProvider: () -> StreamQuality,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val _selected = MutableStateFlow<Map<String, AudioStream>>(emptyMap())

    /** Stream actually chosen per videoId, for the format badges in the UI. */
    val selected: StateFlow<Map<String, AudioStream>> = _selected.asStateFlow()

    suspend fun resolve(videoId: String): ResolvedStream {
        cache[videoId]?.takeIf { it.expiresAtMs - EXPIRY_MARGIN_MS > clock() }?.let { return it }
        return client.player(videoId).also { cache[videoId] = it }
    }

    suspend fun resolvePlayback(videoId: String): AudioStream {
        val resolved = resolve(videoId)
        val stream = AudioStreamSelector.selectForPlayback(resolved.streams, qualityProvider())
            ?: throw ExtractionException("Tidak ada stream audio yang cocok")
        markSelected(videoId, stream)
        return stream
    }

    internal fun markSelected(videoId: String, stream: AudioStream) {
        _selected.update { it + (videoId to stream) }
    }

    fun loudnessDb(videoId: String): Float? = cache[videoId]?.loudnessDb

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 5 * 60_000L
    }
}
