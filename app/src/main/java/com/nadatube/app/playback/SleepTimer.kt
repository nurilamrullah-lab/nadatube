package com.nadatube.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Counting(val endsAtMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/** App-scoped sleep timer; the playback service registers how to pause/fade the player. */
class SleepTimer(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()
    private var job: Job? = null

    /** Set by the service: fades out and pauses. */
    var onExpire: suspend () -> Unit = {}

    fun start(minutes: Int) {
        cancel()
        val endsAt = clock() + minutes * 60_000L
        _state.value = SleepTimerState.Counting(endsAt)
        job = scope.launch {
            while (isActive && clock() < endsAt) delay(1_000)
            if (isActive) expire()
        }
    }

    fun stopAtEndOfTrack() {
        cancel()
        _state.value = SleepTimerState.EndOfTrack
    }

    /** Called by the service when a track ends while in end-of-track mode. */
    suspend fun onTrackEnded() {
        if (_state.value == SleepTimerState.EndOfTrack) expire()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.Off
    }

    private suspend fun expire() {
        _state.value = SleepTimerState.Off
        onExpire()
    }

    fun remainingMs(): Long = (state.value as? SleepTimerState.Counting)?.let { (it.endsAtMs - clock()).coerceAtLeast(0) } ?: 0
}
