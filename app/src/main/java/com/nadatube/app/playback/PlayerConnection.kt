package com.nadatube.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.nadatube.app.data.LibraryRepository
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.model.SponsorSegment
import com.nadatube.app.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shared between the service and UI (same process). */
class NowPlayingState {
    val segments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val skipped = MutableSharedFlow<SponsorSegment>(extraBufferCapacity = 4)
}

data class PlayerUiState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0,
    val error: String? = null,
)

/** UI-side handle to the playback service via a MediaController. */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val innerTube: InnerTubeClient,
) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var progressJob: Job? = null
    private var radioJob: Job? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }
            pending.clear()
            if (c.isPlaying) startProgress()
        }, MoreExecutors.directExecutor())
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    // ---------------------------------------------------------------- commands

    /** Plays [tracks] from [startIndex]. A single track is extended with an automix radio. */
    fun play(tracks: List<Track>, startIndex: Int = 0, withRadio: Boolean = tracks.size == 1) {
        if (tracks.isEmpty()) return
        radioJob?.cancel()
        scope.launch {
            val items = tracks.map { toMediaItem(it) }
            withController { c ->
                c.setMediaItems(items, startIndex, 0L)
                c.prepare()
                c.play()
            }
            if (withRadio) appendRadio(tracks[startIndex])
        }
    }

    fun play(track: Track) = play(listOf(track))

    fun playNext(track: Track) = scope.launch {
        val item = toMediaItem(track)
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItem(item); c.prepare(); c.play()
            } else {
                c.addMediaItem(c.currentMediaItemIndex + 1, item)
            }
        }
    }

    fun addToQueue(track: Track) = scope.launch {
        val item = toMediaItem(track)
        withController { c ->
            c.addMediaItem(item)
            if (c.mediaItemCount == 1) { c.prepare(); c.play() }
        }
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); _state.value = _state.value.copy(positionMs = ms) }
    fun skipToQueueItem(index: Int) = withController { it.seekToDefaultPosition(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 2f)) }

    private suspend fun toMediaItem(track: Track): MediaItem {
        library.remember(track)
        return MediaItems.from(track, library.offlineFile(track.videoId)?.absolutePath)
    }

    private fun appendRadio(seed: Track) {
        radioJob = scope.launch {
            val radio = runCatching { innerTube.radio(seed.videoId) }.getOrNull().orEmpty()
                .filter { it.videoId != seed.videoId }.take(30)
            if (radio.isEmpty()) return@launch
            val items = radio.map { toMediaItem(it) }
            withController { c ->
                if (c.currentMediaItem?.mediaId == seed.videoId) c.addMediaItems(items)
            }
        }
    }

    // ---------------------------------------------------------------- state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (player.isPlaying) startProgress() else progressJob?.cancel()
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { MediaItems.toTrack(c.getMediaItemAt(it)) }
        _state.value = PlayerUiState(
            track = c.currentMediaItem?.let(MediaItems::toTrack),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            bufferedMs = c.bufferedPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.let(MediaItems::toTrack)?.durationMs ?: 0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            queue = queue,
            queueIndex = c.currentMediaItemIndex,
            error = c.playerError?.let { it.cause?.message ?: it.message },
        )
    }

    /** Lets screen-render tests show the player without a running media session. */
    @androidx.annotation.VisibleForTesting
    internal fun overrideStateForTest(state: PlayerUiState) {
        _state.value = state
    }

    private fun startProgress() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    _state.value = _state.value.copy(positionMs = c.currentPosition, bufferedMs = c.bufferedPosition)
                }
                delay(500)
            }
        }
    }
}
