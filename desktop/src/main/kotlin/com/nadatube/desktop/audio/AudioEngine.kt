package com.nadatube.desktop.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

data class EngineState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Decodes an [AacTrack] on a dedicated thread and feeds a [PcmSink]. Seeking and loading restart
 * the thread with a fresh sink, which also resets the sink's played-time clock.
 */
class AudioEngine(private val sinkFactory: (sampleRate: Int, channels: Int) -> PcmSink) {
    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Called on the decoder thread when a track plays to its end (not when it is replaced). */
    var onEnded: () -> Unit = {}
    var onError: (Throwable) -> Unit = {}

    @Volatile var volume: Float = 1f

    private var track: AacTrack? = null
    private var worker: Thread? = null
    private var generation = 0
    private var wantPlaying = false
    @Volatile private var sink: PcmSink? = null

    fun load(track: AacTrack, startMs: Long = 0, play: Boolean = true) {
        val gen = lock.withLock { generation++; generation }
        stopWorker()
        this.track = track
        lock.withLock { wantPlaying = play }
        _state.value = EngineState(startMs, track.durationMs, play, true)
        start(track, startMs, gen)
    }

    fun play() = lock.withLock {
        if (track == null) return@withLock
        wantPlaying = true
        sink?.resume()
        _state.value = _state.value.copy(playing = true)
        resumed.signalAll()
    }

    fun pause() = lock.withLock {
        wantPlaying = false
        sink?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    fun seek(ms: Long) {
        val t = track ?: return
        val gen = lock.withLock { generation++; generation }
        stopWorker()
        _state.value = _state.value.copy(positionMs = ms.coerceIn(0, t.durationMs))
        start(t, ms, gen)
    }

    fun stop() {
        lock.withLock { generation++; wantPlaying = false }
        stopWorker()
        track = null
        _state.value = EngineState()
    }

    private fun stopWorker() {
        val w = worker ?: return
        lock.withLock { resumed.signalAll() }
        sink?.let { runCatching { it.close() } }
        w.interrupt()
        if (Thread.currentThread() !== w) w.join(2_000)
        worker = null
        sink = null
    }

    private fun start(track: AacTrack, startMs: Long, gen: Int) {
        val begin = track.indexAt(startMs)
        val baseMs = track.startMs(begin)
        worker = thread(name = "nadatube-audio", isDaemon = true) {
            try {
                val decoder = AacDecoder(track.config)
                val out = sinkFactory(track.sampleRate, track.channels)
                sink = out
                if (!lock.withLock { wantPlaying }) out.pause()
                // AAC has 1 frame of overlap: decode the preceding frame (discarded) after a seek.
                if (begin > 0) runCatching { decoder.decode(track.frame(begin - 1)) }
                var i = begin
                while (i < track.samples.size) {
                    lock.withLock {
                        while (!wantPlaying && generation == gen) resumed.await()
                        if (generation != gen) return@thread
                    }
                    val pcm = decoder.decode(track.frame(i))
                    Pcm.applyVolume(pcm, volume)
                    out.write(pcm)
                    if (generation != gen) return@thread
                    _state.value = _state.value.copy(positionMs = baseMs + out.playedMs())
                    i++
                }
                if (generation == gen) {
                    _state.value = _state.value.copy(positionMs = track.durationMs, playing = false)
                    onEnded()
                }
            } catch (_: InterruptedException) {
                // replaced by load/seek/stop
            } catch (e: Throwable) {
                if (generation == gen) { _state.value = _state.value.copy(playing = false); onError(e) }
            }
        }
    }
}
