package com.nadatube.app.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioEngineLogicTest {
    @Test
    fun `presets all have ten bands within range`() {
        EqualizerPresets.ALL.forEach { p ->
            assertEquals(p.name, EqualizerPresets.BAND_COUNT, p.gainsDb.size)
            assertTrue(p.gainsDb.all { abs(it) <= EqualizerPresets.MAX_GAIN_DB })
        }
    }

    @Test
    fun `cutoffs sit between band centres and increase`() {
        val c = EqualizerPresets.cutoffFrequencies()
        assertEquals(10, c.size)
        c.forEachIndexed { i, f ->
            assertTrue(f > EqualizerPresets.FREQUENCIES[i])
            if (i > 0) assertTrue(f > c[i - 1])
        }
    }

    @Test
    fun `interpolation onto device bands`() {
        val g = listOf(0f, 0f, 0f, 0f, 0f, 10f, 0f, 0f, 0f, 0f)
        assertEquals(10f, EqualizerPresets.interpolate(g, 1_000), 0.001f)
        assertEquals(5f, EqualizerPresets.interpolate(g, 1_414), 0.05f) // geometric midpoint 1k..2k
        assertEquals(0f, EqualizerPresets.interpolate(g, 10), 0.001f)
        assertEquals(0f, EqualizerPresets.interpolate(g, 20_000), 0.001f)
    }

    @Test
    fun `loudness normalisation`() {
        assertEquals(-3f, LoudnessNormalizer.gainDb(3f, true), 0f)
        assertEquals(6f, LoudnessNormalizer.gainDb(-20f, true), 0f) // boost capped
        assertEquals(-12f, LoudnessNormalizer.gainDb(30f, true), 0f) // cut capped
        assertEquals(0f, LoudnessNormalizer.gainDb(3f, false), 0f)
        assertEquals(0f, LoudnessNormalizer.gainDb(null, true), 0f)
        assertEquals(0.5f, LoudnessNormalizer.playerVolume(-6.0206f), 0.001f)
        assertEquals(1f, LoudnessNormalizer.playerVolume(4f), 0f)
        assertEquals(400, LoudnessNormalizer.boostMillibels(4f))
        assertEquals(0, LoudnessNormalizer.boostMillibels(-4f))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sleep timer expires after duration`() = runTest {
        val scope = TestScope(testScheduler)
        val timer = SleepTimer(scope) { testScheduler.currentTime }
        var expired = false
        timer.onExpire = { expired = true }
        timer.start(1)
        assertTrue(timer.state.value is SleepTimerState.Counting)
        advanceTimeBy(59_000); scope.runCurrent()
        assertFalse(expired)
        advanceTimeBy(2_000); scope.runCurrent()
        assertTrue(expired)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `sleep timer end of track and cancel`() = runTest {
        val timer = SleepTimer(this)
        var expired = 0
        timer.onExpire = { expired++ }
        timer.stopAtEndOfTrack()
        timer.onTrackEnded()
        assertEquals(1, expired)
        timer.onTrackEnded() // already off
        assertEquals(1, expired)
        timer.start(10)
        timer.cancel()
        assertEquals(SleepTimerState.Off, timer.state.value)
    }
}
