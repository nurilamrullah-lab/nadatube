package com.nadatube.app.playback

import kotlin.math.ln
import kotlin.math.pow

data class EqPreset(val name: String, val label: String, val gainsDb: List<Float>)

/** 10-band graphic EQ definition plus pure helpers used by [AudioEffectsController]. */
object EqualizerPresets {
    const val BAND_COUNT = 10
    const val MAX_GAIN_DB = 12f

    /** ISO octave centre frequencies in Hz. */
    val FREQUENCIES = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    val FLAT = EqPreset("FLAT", "Datar", List(BAND_COUNT) { 0f })
    val ALL = listOf(
        FLAT,
        EqPreset("BASS", "Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("VOCAL", "Vokal", listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f)),
        EqPreset("ACOUSTIC", "Akustik", listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 2f, 1f)),
        EqPreset("ROCK", "Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
        EqPreset("POP", "Pop", listOf(-1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1f, -1f)),
        EqPreset("ELECTRONIC", "Elektronik", listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f)),
        EqPreset("TREBLE", "Treble", listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 6f)),
        EqPreset("NIGHT", "Malam", listOf(-3f, -2f, -1f, 0f, 1f, 2f, 2f, 1f, -1f, -3f)),
    )

    fun byName(name: String): EqPreset? = ALL.firstOrNull { it.name == name }

    /** Upper edge of each band (geometric mean with the next centre), for DynamicsProcessing. */
    fun cutoffFrequencies(): List<Float> = FREQUENCIES.mapIndexed { i, f ->
        val next = FREQUENCIES.getOrNull(i + 1) ?: 20_000
        kotlin.math.sqrt(f.toDouble() * next).toFloat()
    }

    /**
     * Maps our 10-band curve onto a device equalizer with arbitrary centre frequencies
     * (legacy android.media.audiofx.Equalizer usually has 5), interpolating on a log scale.
     */
    fun interpolate(gainsDb: List<Float>, targetHz: Int): Float {
        require(gainsDb.size == BAND_COUNT)
        if (targetHz <= FREQUENCIES.first()) return gainsDb.first()
        if (targetHz >= FREQUENCIES.last()) return gainsDb.last()
        val i = FREQUENCIES.indexOfLast { it <= targetHz }
        val lo = FREQUENCIES[i]
        val hi = FREQUENCIES[i + 1]
        val t = (ln(targetHz.toDouble()) - ln(lo.toDouble())) / (ln(hi.toDouble()) - ln(lo.toDouble()))
        return (gainsDb[i] + (gainsDb[i + 1] - gainsDb[i]) * t).toFloat()
    }

    fun clamp(gainDb: Float) = gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
}

/** Volume normalisation using YouTube's per-track loudness measurement. */
object LoudnessNormalizer {
    const val MAX_BOOST_DB = 6f
    const val MAX_CUT_DB = 12f

    /** YouTube reports loudness relative to its reference; positive means "too loud". */
    fun gainDb(loudnessDb: Float?, enabled: Boolean): Float {
        if (!enabled || loudnessDb == null || loudnessDb.isNaN()) return 0f
        return (-loudnessDb).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
    }

    /** Linear player volume for the attenuation part (boost is applied by an audio effect). */
    fun playerVolume(gainDb: Float): Float = if (gainDb >= 0f) 1f else 10f.pow(gainDb / 20f)

    /** Gain to apply with an effect (millibels for LoudnessEnhancer). */
    fun boostMillibels(gainDb: Float): Int = if (gainDb <= 0f) 0 else (gainDb * 100).toInt()
}
