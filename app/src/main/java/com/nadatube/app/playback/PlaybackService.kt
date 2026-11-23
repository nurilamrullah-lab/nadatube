package com.nadatube.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nadatube.app.AppContainer
import com.nadatube.app.MainActivity
import com.nadatube.app.NadaTubeApp
import com.nadatube.app.R
import com.nadatube.app.sponsorblock.SponsorSkipLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background playback: ExoPlayer inside a MediaSessionService. Media3 provides the MediaStyle
 * notification, lockscreen controls and headset/Bluetooth media buttons from the session.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var container: AppContainer
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effects = AudioEffectsController()
    private var sponsorJob: Job? = null
    private var sponsorLoadJob: Job? = null
    private val skippedSegments = mutableSetOf<String>()
    private val retries = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        container = (application as NadaTubeApp).container
        player = ExoPlayer.Builder(this, renderersFactory())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        effects.onPlayerVolume = { player.volume = it }
        scope.launch { container.settings.equalizer.collect { effects.update(it) } }
        container.sleepTimer.onExpire = { fadeOutAndPause() }
        effects.attach(player.audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        effects.release()
        container.sleepTimer.onExpire = {}
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pipeline

    private fun renderersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(container.visualizer)))
                .build()
    }

    /** See [StreamDataSources]. */
    private fun dataSourceFactory() = StreamDataSources.create(
        this, container.httpClient, container.streamResolver, container.audioCache,
        container.settings.cacheKey(), { container.settings.quality.value },
    )

    // ------------------------------------------------------------------ listeners

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                scope.launch { container.sleepTimer.onTrackEnded() }
            }
            onNewItem(mediaItem)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startSponsorLoop() else sponsorJob?.cancel()
            if (!isPlaying) container.visualizer.reset()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { container.sleepTimer.onTrackEnded() }
            if (playbackState == Player.STATE_READY) {
                player.currentMediaItem?.let { applyNormalization(it.mediaId) }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            val attempts = retries[id] ?: 0
            when (PlaybackRecovery.decide(PlaybackRecovery.httpCode(error), attempts, PlaybackRecovery.isExtractionFailure(error))) {
                // Rejected/expired URL: re-resolve (skipping the client that issued it) and
                // resume where playback stopped; prepare() keeps the current position.
                PlaybackRecovery.Action.REJECT_AND_RETRY -> {
                    container.streamResolver.reject(id)
                    retries[id] = attempts + 1
                    player.prepare()
                }
                PlaybackRecovery.Action.RETRY -> {
                    retries[id] = attempts + 1
                    player.prepare()
                }
                PlaybackRecovery.Action.GIVE_UP -> container.streamResolver.invalidate(id)
            }
        }
    }

    private fun onNewItem(item: MediaItem?) {
        retries.clear()
        skippedSegments.clear()
        container.nowPlaying.segments.value = emptyList()
        sponsorLoadJob?.cancel()
        item ?: return
        val track = MediaItems.toTrack(item)
        scope.launch { runCatching { container.library.recordPlay(track) } }
        sponsorLoadJob = scope.launch {
            if (!container.settings.sponsorBlockEnabled.value) return@launch
            val segments = runCatching { container.sponsorBlock.segments(track.videoId) }.getOrDefault(emptyList())
            if (player.currentMediaItem?.mediaId == track.videoId) container.nowPlaying.segments.value = segments
        }
        applyNormalization(track.videoId)
    }

    private fun applyNormalization(videoId: String) {
        val enabled = container.settings.equalizer.value.normalize
        effects.setNormalizationGain(LoudnessNormalizer.gainDb(container.streamResolver.loudnessDb(videoId), enabled))
    }

    private fun startSponsorLoop() {
        sponsorJob?.cancel()
        sponsorJob = scope.launch {
            while (isActive) {
                checkSponsorSkip()
                delay(250)
            }
        }
    }

    private fun checkSponsorSkip() {
        if (!container.settings.sponsorBlockEnabled.value) return
        val segments = container.nowPlaying.segments.value
        if (segments.isEmpty()) return
        val segment = SponsorSkipLogic.segmentToSkip(
            player.currentPosition, segments, skippedSegments, container.settings.sponsorCategories.value,
        ) ?: return
        skippedSegments += segment.uuid
        val target = SponsorSkipLogic.seekTarget(segment, player.duration.coerceAtLeast(0))
        if (target == null) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(player.duration)
        } else {
            player.seekTo(target)
        }
        container.nowPlaying.skipped.tryEmit(segment)
    }

    private suspend fun fadeOutAndPause() {
        val start = player.volume
        for (i in 10 downTo 0) {
            player.volume = start * i / 10f
            delay(300)
        }
        player.pause()
        player.volume = start
    }
}
