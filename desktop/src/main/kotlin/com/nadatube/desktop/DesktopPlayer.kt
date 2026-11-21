package com.nadatube.desktop

import com.nadatube.app.extractor.AudioStreamSelector
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.Ipv4FirstDns
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.extractor.StreamRangeInterceptor
import com.nadatube.app.extractor.StreamRejectedException
import com.nadatube.app.extractor.StreamResolver
import com.nadatube.app.lyrics.LyricsClient
import com.nadatube.app.model.SponsorSegment
import com.nadatube.app.model.Track
import com.nadatube.app.sponsorblock.SponsorBlockClient
import com.nadatube.app.sponsorblock.SponsorSkipLogic
import com.nadatube.desktop.audio.AacTrack
import com.nadatube.desktop.audio.AudioCache
import com.nadatube.desktop.audio.AudioEngine
import com.nadatube.desktop.audio.JavaSoundSink
import com.nadatube.desktop.audio.Mp4Demuxer
import com.nadatube.desktop.audio.PcmSink
import com.nadatube.desktop.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }
enum class Status { IDLE, LOADING, PLAYING, PAUSED }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val status: Status = Status.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val segments: List<SponsorSegment> = emptyList(),
    val loadProgress: Float = 0f,
    val error: String? = null,
    val lastSkipped: SponsorSegment? = null,
) {
    val track: Track? get() = queue.getOrNull(index)
}

/** Everything the desktop app talks to; one instance per process (tests build their own). */
class Services(
    val dataDir: File,
    val store: Store = Store(File(dataDir, "state.json")),
    baseUrl: String = "https://www.youtube.com",
    musicBaseUrl: String = "https://music.youtube.com",
    sponsorBaseUrl: String = "https://sponsor.ajay.app",
    lyricsBaseUrl: String = "https://lrclib.net",
    sinkFactory: (Int, Int) -> PcmSink = { rate, ch -> JavaSoundSink(rate, ch) },
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns())
        .addInterceptor(StreamRangeInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val innerTube = InnerTubeClient(
        http, baseUrl, musicBaseUrl,
        initialVisitorData = store.state.value.visitorData,
        onVisitorData = { v -> store.update { it.copy(visitorData = v) } },
    )
    val resolver = StreamResolver(innerTube, { StreamQuality.BEST })
    val sponsorBlock = SponsorBlockClient(http, sponsorBaseUrl)
    val lyrics = LyricsClient(http, lyricsBaseUrl)
    val cache = AudioCache(File(dataDir, "cache"), http = http)
    val engine = AudioEngine(sinkFactory)
}

/**
 * Queue, loading and transport. Streams are downloaded whole into [AudioCache], demuxed and
 * decoded by [AudioEngine]; SponsorBlock segments are skipped by watching the playback position.
 */
class DesktopPlayer(private val services: Services, private val scope: CoroutineScope) {
    private val engine = services.engine
    private val _state = MutableStateFlow(PlayerState(volume = services.store.state.value.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private val skipped = mutableSetOf<String>()

    init {
        engine.volume = _state.value.volume
        engine.onEnded = { scope.launch { onTrackEnded() } }
        engine.onError = { e -> scope.launch { _state.value = _state.value.copy(status = Status.PAUSED, error = "Gagal memutar: ${e.message}") } }
        scope.launch {
            engine.state.collectLatest { e ->
                val cur = _state.value
                if (cur.status == Status.LOADING) return@collectLatest
                _state.value = cur.copy(
                    positionMs = e.positionMs,
                    durationMs = if (e.durationMs > 0) e.durationMs else cur.durationMs,
                    status = when {
                        !e.loaded -> cur.status
                        e.playing -> Status.PLAYING
                        else -> Status.PAUSED
                    },
                )
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value.status == Status.PLAYING) checkSponsorSkip()
            }
        }
    }

    // ---------------------------------------------------------------- queue & transport

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        _state.value = _state.value.copy(queue = tracks, index = startIndex.coerceIn(tracks.indices))
        loadCurrent()
    }

    fun play(track: Track) = play(listOf(track))

    fun skipTo(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.value = _state.value.copy(index = index)
        loadCurrent()
    }

    fun enqueue(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) play(track) else _state.value = s.copy(queue = s.queue + track)
    }

    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) return play(track)
        val at = s.index + 1
        _state.value = s.copy(queue = s.queue.toMutableList().also { it.add(at, track) })
    }

    fun togglePlay() {
        when (_state.value.status) {
            Status.PLAYING -> engine.pause()
            Status.PAUSED -> if (engine.state.value.loaded) engine.play() else loadCurrent()
            else -> Unit
        }
    }

    fun seek(ms: Long) {
        if (!engine.state.value.loaded) return
        engine.seek(ms)
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun next() { advance(auto = false) }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 3_000 || s.index <= 0) return seek(0)
        skipTo(s.index - 1)
    }

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        engine.volume = vol
        _state.value = _state.value.copy(volume = vol)
        services.store.update { it.copy(volume = vol) }
    }

    fun toggleShuffle() { _state.value = _state.value.copy(shuffle = !_state.value.shuffle) }

    fun cycleRepeat() {
        _state.value = _state.value.copy(repeat = RepeatMode.entries[(_state.value.repeat.ordinal + 1) % RepeatMode.entries.size])
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ---------------------------------------------------------------- loading

    private fun onTrackEnded() {
        if (_state.value.repeat == RepeatMode.ONE) engine.seek(0).also { engine.play() } else advance(auto = true)
    }

    private fun advance(auto: Boolean) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val next = when {
            s.shuffle && s.queue.size > 1 -> generateSequence { Random.nextInt(s.queue.size) }.first { it != s.index }
            s.index + 1 < s.queue.size -> s.index + 1
            s.repeat == RepeatMode.ALL || !auto -> 0
            else -> {
                _state.value = s.copy(status = Status.PAUSED, positionMs = 0)
                return
            }
        }
        skipTo(next)
    }

    private fun loadCurrent() {
        val track = _state.value.track ?: return
        loadJob?.cancel()
        prefetchJob?.cancel()
        skipped.clear()
        _state.value = _state.value.copy(
            status = Status.LOADING, positionMs = 0, durationMs = track.durationMs, segments = emptyList(),
            loadProgress = 0f, error = null, lastSkipped = null,
        )
        loadJob = scope.launch {
            try {
                // Closing the audio line can block briefly; keep it off the UI thread.
                withContext(Dispatchers.IO) { engine.stop() }
                val aac = withContext(Dispatchers.IO) { fetchTrack(track) }
                if (_state.value.track?.videoId != track.videoId) return@launch
                engine.volume = _state.value.volume
                _state.value = _state.value.copy(status = Status.PLAYING, durationMs = aac.durationMs)
                engine.load(aac, 0, play = true)
                services.store.recordPlay(track)
                launch { loadSegments(track) }
                prefetchNext()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = Status.PAUSED, error = e.message ?: "Gagal memuat lagu")
            }
        }
    }

    /** Cached file if present, otherwise resolve → pick AAC → download. Retries once on a refused URL. */
    private suspend fun fetchTrack(track: Track): AacTrack {
        services.cache.cached(track.videoId)?.let { return Mp4Demuxer.parse(it.readBytes()) }
        var lastError: Exception? = null
        repeat(2) {
            try {
                val file = download(track)
                return Mp4Demuxer.parse(file.readBytes())
            } catch (e: StreamRejectedException) {
                services.resolver.reject(track.videoId)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Gagal memuat lagu")
    }

    private suspend fun download(track: Track): File {
        val resolved = services.resolver.resolve(track.videoId)
        // JavaSound/JAAD decode AAC but not Opus, so desktop streams the AAC rendition.
        val stream = AudioStreamSelector.selectForDownload(resolved.streams)
            ?.takeIf { it.mimeType.startsWith("audio/mp4") }
            ?: throw IllegalStateException("Tidak ada stream AAC untuk lagu ini")
        return services.cache.fetch(
            track.videoId, stream, resolved.userAgent ?: InnerTubeClient.STREAM_USER_AGENT, resolved.clientName,
        ) { p -> _state.value = _state.value.copy(loadProgress = p) }
    }

    private fun prefetchNext() {
        val s = _state.value
        val next = s.queue.getOrNull(s.index + 1) ?: return
        if (services.cache.cached(next.videoId) != null) return
        prefetchJob = scope.launch(Dispatchers.IO) { runCatching { download(next) } }
    }

    private suspend fun loadSegments(track: Track) {
        if (!services.store.state.value.sponsorBlock) return
        val segments = runCatching { services.sponsorBlock.segments(track.videoId) }.getOrDefault(emptyList())
        if (_state.value.track?.videoId == track.videoId) _state.value = _state.value.copy(segments = segments)
    }

    private fun checkSponsorSkip() {
        val s = _state.value
        if (s.segments.isEmpty() || !services.store.state.value.sponsorBlock) return
        val seg = SponsorSkipLogic.segmentToSkip(s.positionMs, s.segments, skipped) ?: return
        skipped += seg.uuid
        _state.value = s.copy(lastSkipped = seg)
        val target = SponsorSkipLogic.seekTarget(seg, s.durationMs)
        if (target == null) advance(auto = true) else seek(target)
    }
}
