package com.nadatube.desktop.audio

import com.nadatube.app.download.Mp4TagWriter
import com.nadatube.app.extractor.AudioStreamSelector
import com.nadatube.app.extractor.StreamResolver
import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.AudioStream
import com.nadatube.app.model.SponsorSegment
import com.nadatube.app.model.Track
import com.nadatube.app.playback.SleepTimer
import com.nadatube.app.sponsorblock.SponsorBlockClient
import com.nadatube.desktop.data.DesktopStore
import java.io.File
import javax.sound.sampled.AudioFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR }

data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 0.8f,
    val error: String? = null,
)

/**
 * Desktop playback engine.
 *
 * YouTube AAC (itag 140) is downloaded in full to the cache first (URLs expire after a
 * few hours and the stream is short-lived), then demuxed with [Fmp4Demuxer] and decoded
 * frame-by-frame with [AacDecoder] (JAAD) into an [AudioSink] (JavaSound).
 * Opus streams cannot be decoded on the JVM without a native library, so the player
 * refuses them with a clear error instead of failing silently.
 */
class DesktopPlayer(
    private val resolver: StreamResolver,
    private val http: OkHttpClient,
    private val sponsorBlock: SponsorBlockClient,
    private val store: DesktopStore,
    private val cacheDir: File,
    private val sinkFactory: (AudioFormat) -> AudioSink = { JavaSoundAudioSink() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val sleepTimer = SleepTimer(scope).apply { onExpire = { stop() } }

    private var playbackJob: Job? = null
    private var sink: AudioSink? = null

    @Volatile private var paused = false
    @Volatile private var seekRequestMs: Long? = null
    @Volatile private var frames: List<Fmp4Demuxer.AacFrame> = emptyList()
    @Volatile private var frameIndex = 0

    fun play(track: Track, queue: List<Track> = listOf(track)) {
        val index = queue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        _state.update { it.copy(queue = queue, queueIndex = index) }
        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                playInternal(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = PlaybackStatus.ERROR, error = e.message) }
            }
        }
    }

    /** Test/smoke seam: play raw fMP4 bytes without any network. */
    fun playFromBytes(track: Track, bytes: ByteArray, itag: Int = 140) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            try {
                val demuxed = Fmp4Demuxer.demux(bytes)
                runDecodeLoop(track, demuxed, emptyList(), itag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = PlaybackStatus.ERROR, error = e.message) }
            }
        }
    }

    fun pause() {
        if (_state.value.status == PlaybackStatus.PLAYING) {
            paused = true
            _state.update { it.copy(status = PlaybackStatus.PAUSED) }
        }
    }

    fun resume() {
        if (_state.value.status == PlaybackStatus.PAUSED) {
            paused = false
            _state.update { it.copy(status = PlaybackStatus.PLAYING) }
        }
    }

    fun togglePlayPause() {
        when (_state.value.status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            else -> Unit
        }
    }

    fun stop() {
        playbackJob?.cancel()
        sink?.close()
        sink = null
        paused = false
        _state.update { it.copy(status = PlaybackStatus.STOPPED, positionMs = 0) }
    }

    fun next() {
        val s = _state.value
        if (s.queueIndex + 1 < s.queue.size) play(s.queue[s.queueIndex + 1], s.queue)
    }

    /** Menambah lagu ke antrean (mis. hasil radio/autoplay) tanpa mengganggu yang sedang diputar. */
    fun appendToQueue(tracks: List<Track>) {
        val s = _state.value
        val existing = s.queue.map { it.videoId }.toSet()
        val fresh = tracks.filterNot { it.videoId in existing }
        if (fresh.isNotEmpty()) _state.update { it.copy(queue = it.queue + fresh) }
    }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 3000) {
            seekTo(0)
        } else if (s.queueIndex > 0) {
            play(s.queue[s.queueIndex - 1], s.queue)
        }
    }

    fun seekTo(positionMs: Long) {
        seekRequestMs = positionMs.coerceAtLeast(0)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        sink?.setVolume(v)
    }

    fun setSleepMinutes(minutes: Int) = sleepTimer.start(minutes)
    fun sleepUntilEndOfTrack() = sleepTimer.stopAtEndOfTrack()
    fun cancelSleep() = sleepTimer.cancel()
    fun sleepRemainingMs(): Long = sleepTimer.remainingMs()

    // ------------------------------------------------------------ internals

    private suspend fun playInternal(track: Track) {
        _state.update { it.copy(status = PlaybackStatus.LOADING, track = track, positionMs = 0, durationMs = track.durationMs, error = null) }
        val resolved = resolver.resolve(track.videoId)
        val stream = AudioStreamSelector.selectForDownload(resolved.streams)
            ?: throw IllegalStateException("Tidak ada stream audio untuk video ini")
        if (stream.codec != AudioCodec.AAC) {
            throw IllegalStateException(
                "Stream ${stream.codec.label} belum didukung di desktop — hanya AAC (itag 140). Coba lagi, server biasanya menyediakan AAC.",
            )
        }
        val bytes = downloadStreamBytes(track.videoId, stream)
        val demuxed = Fmp4Demuxer.demux(bytes)
        val segments = runCatching { sponsorBlock.segments(track.videoId) }.getOrElse { emptyList() }
        store.recordHistory(track)
        runDecodeLoop(track, demuxed, segments, stream.itag)
    }

    private suspend fun runDecodeLoop(
        track: Track,
        demuxed: Fmp4Demuxer.AacTrack,
        segments: List<SponsorSegment>,
        itag: Int,
    ) {
        frames = demuxed.frames
        frameIndex = 0
        paused = false
        val decoder = AacDecoder(demuxed.audioSpecificConfig)
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            decoder.sampleRate.toFloat(),
            16,
            decoder.channels,
            decoder.channels * 2,
            decoder.sampleRate.toFloat(),
            false, // little-endian
        )
        val sink = sinkFactory(format).also { this.sink = it }
        sink.open(format)
        sink.setVolume(_state.value.volume)
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                track = track,
                durationMs = demuxed.durationMs,
                positionMs = 0,
                error = null,
            )
        }
        try {
            var i = 0
            while (i < frames.size && scope.coroutineContext.isActive) {
                while (paused) {
                    delay(50)
                    if (!scope.coroutineContext.isActive) return
                }
                seekRequestMs?.let { target ->
                    seekRequestMs = null
                    i = frames.indexOfFirst { it.timestampUs / 1000 >= target }.takeIf { it >= 0 } ?: frames.size
                }
                if (i >= frames.size) break
                val frame = frames[i]
                // SponsorBlock: lompat ke akhir segmen bila posisi masuk ke dalamnya.
                val skipTo = segments.firstOrNull {
                    frame.timestampUs / 1000 in it.startMs until it.endMs
                }?.let { seg -> frames.indexOfFirst { f -> f.timestampUs / 1000 >= seg.endMs } }
                if (skipTo != null && skipTo > i) {
                    i = skipTo
                    continue
                }
                val pcm = decoder.decodeFrame(frame.data)
                sink.write(pcm)
                frameIndex = i
                _state.update { it.copy(positionMs = frame.timestampUs / 1000) }
                i++
            }
            sink.drain()
            if (scope.coroutineContext.isActive) {
                sleepTimer.onTrackEnded()
                val s = _state.value
                if (s.queueIndex + 1 < s.queue.size && s.queueIndex >= 0) {
                    play(s.queue[s.queueIndex + 1], s.queue)
                } else {
                    _state.update { it.copy(status = PlaybackStatus.STOPPED, positionMs = 0) }
                }
            }
        } finally {
            sink.close()
            if (this.sink === sink) this.sink = null
            decoder.close()
        }
    }

    /** Downloads the stream to the cache and returns its bytes (reuses a cached file). */
    suspend fun downloadStreamBytes(videoId: String, stream: AudioStream): ByteArray =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val file = File(cacheDir, "${videoId}_${stream.itag}.${stream.extension}")
            if (file.exists() && file.length() > 0) return@withContext file.readBytes()
            val request = Request.Builder()
                .url(stream.url)
                .header("User-Agent", resolver.userAgent(videoId))
                .header("Accept", "*/*")
                .build()
            val body = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Unduh stream gagal: HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IllegalStateException("Stream kosong")
            }
            file.writeBytes(body)
            body
        }

    /** Downloads a track as an offline file (with ID3-ish MP4 tags) into the downloads dir. */
    suspend fun downloadTrack(track: Track): File {
        val resolved = resolver.resolve(track.videoId)
        val stream = AudioStreamSelector.selectForDownload(resolved.streams)
            ?: throw IllegalStateException("Tidak ada stream audio untuk video ini")
        val bytes = downloadStreamBytes(track.videoId, stream)
        val dir = store.downloadDir()
        dir.mkdirs()
        val safeTitle = track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        val file = File(dir, "$safeTitle.${stream.extension}")
        file.writeBytes(bytes)
        runCatching {
            val cover = track.thumbnailUrl?.let { url ->
                http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
            }
            Mp4TagWriter.write(
                file,
                Mp4TagWriter.Tags(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    cover = cover,
                    coverIsPng = false,
                ),
            )
        }
        store.recordDownload(track, file)
        return file
    }
}
