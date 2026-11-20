package com.nadatube.app.playback

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import com.nadatube.app.data.EqualizerSettings

/**
 * Applies the EQ curve and loudness normalisation to the player's audio session.
 * API 28+: a single DynamicsProcessing instance with a true 10-band pre-EQ, input gain and a
 * limiter to prevent clipping. API 26–27: the platform Equalizer (device bands, interpolated
 * from our 10-band curve) plus LoudnessEnhancer.
 */
class AudioEffectsController {
    private var sessionId = 0
    private var dynamics: DynamicsProcessing? = null
    private var legacyEq: Equalizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var settings = EqualizerSettings()
    private var normalizationGainDb = 0f

    /** Called with the linear volume the player should use for attenuation. */
    var onPlayerVolume: (Float) -> Unit = {}

    var deviceBandCount: Int = EqualizerPresets.BAND_COUNT
        private set

    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId || audioSessionId == 0) return
        release()
        sessionId = audioSessionId
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, EqualizerPresets.BAND_COUNT,
                    false, 0,
                    false, 0,
                    true,
                ).build()
                dynamics = DynamicsProcessing(0, audioSessionId, config)
            } else {
                legacyEq = Equalizer(0, audioSessionId).also { deviceBandCount = it.numberOfBands.toInt() }
                enhancer = LoudnessEnhancer(audioSessionId)
            }
        }.onFailure { Log.w(TAG, "Audio effects unavailable", it) }
        apply()
    }

    fun update(settings: EqualizerSettings) {
        this.settings = settings
        apply()
    }

    fun setNormalizationGain(gainDb: Float) {
        normalizationGainDb = gainDb
        apply()
    }

    private fun apply() {
        val gains = settings.gainsDb.mapIndexed { i, g ->
            val bass = if (i < 3) settings.bassBoostDb * (1f - i / 3f) else 0f
            EqualizerPresets.clamp(if (settings.enabled) g + bass else 0f)
        }
        val norm = if (settings.normalize) normalizationGainDb else 0f
        onPlayerVolume(LoudnessNormalizer.playerVolume(norm))
        val boostDb = norm.coerceAtLeast(0f)
        runCatching {
            dynamics?.let { dp ->
                val cutoffs = EqualizerPresets.cutoffFrequencies()
                gains.forEachIndexed { i, g ->
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i], g))
                }
                dp.setInputGainAllChannelsTo(boostDb)
                // Limiter keeps EQ boosts + normalisation from clipping.
                dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
                dp.enabled = settings.enabled || boostDb > 0f
            }
            legacyEq?.let { eq ->
                for (b in 0 until eq.numberOfBands) {
                    val centerHz = eq.getCenterFreq(b.toShort()) / 1000
                    val range = eq.bandLevelRange
                    val mb = (EqualizerPresets.interpolate(gains, centerHz) * 100).toInt()
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
                eq.enabled = settings.enabled
            }
            enhancer?.let {
                it.setTargetGain(LoudnessNormalizer.boostMillibels(boostDb))
                it.enabled = boostDb > 0f
            }
        }.onFailure { Log.w(TAG, "Failed to apply audio effects", it) }
    }

    fun release() {
        runCatching { dynamics?.release() }
        runCatching { legacyEq?.release() }
        runCatching { enhancer?.release() }
        dynamics = null
        legacyEq = null
        enhancer = null
        sessionId = 0
    }

    private companion object {
        const val TAG = "AudioEffects"
    }
}
