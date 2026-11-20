package com.nadatube.app.data

import android.content.Context
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.playback.EqualizerPresets
import com.nadatube.app.sponsorblock.SponsorSkipLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetName: String = EqualizerPresets.FLAT.name,
    val gainsDb: List<Float> = EqualizerPresets.FLAT.gainsDb,
    val normalize: Boolean = true,
    val bassBoostDb: Float = 0f,
)

/** Small SharedPreferences-backed settings store exposed as StateFlows. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("nadatube_settings", Context.MODE_PRIVATE)

    private val _equalizer = MutableStateFlow(loadEq())
    val equalizer: StateFlow<EqualizerSettings> = _equalizer.asStateFlow()

    private val _quality = MutableStateFlow(StreamQuality.valueOf(prefs.getString("quality", StreamQuality.BEST.name)!!))
    val quality: StateFlow<StreamQuality> = _quality.asStateFlow()

    private val _sponsorBlock = MutableStateFlow(prefs.getBoolean("sb_enabled", true))
    val sponsorBlockEnabled: StateFlow<Boolean> = _sponsorBlock.asStateFlow()

    private val _sponsorCategories = MutableStateFlow(
        prefs.getStringSet("sb_categories", SponsorSkipLogic.DEFAULT_CATEGORIES)!!.toSet(),
    )
    val sponsorCategories: StateFlow<Set<String>> = _sponsorCategories.asStateFlow()

    fun updateEqualizer(transform: (EqualizerSettings) -> EqualizerSettings) {
        val next = transform(_equalizer.value)
        _equalizer.value = next
        prefs.edit()
            .putBoolean("eq_enabled", next.enabled)
            .putString("eq_preset", next.presetName)
            .putString("eq_gains", next.gainsDb.joinToString(","))
            .putBoolean("eq_normalize", next.normalize)
            .putFloat("eq_bass", next.bassBoostDb)
            .apply()
    }

    fun setQuality(q: StreamQuality) {
        _quality.value = q
        prefs.edit().putString("quality", q.name).apply()
    }

    fun setSponsorBlockEnabled(enabled: Boolean) {
        _sponsorBlock.value = enabled
        prefs.edit().putBoolean("sb_enabled", enabled).apply()
    }

    fun toggleSponsorCategory(category: String) {
        val next = _sponsorCategories.value.let { if (category in it) it - category else it + category }
        _sponsorCategories.value = next
        prefs.edit().putStringSet("sb_categories", next).apply()
    }

    /** Per-install AES key for the encrypted audio cache. */
    fun cacheKey(): ByteArray {
        prefs.getString("cache_key", null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val key = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString("cache_key", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
        return key
    }

    private fun loadEq(): EqualizerSettings {
        val gains = prefs.getString("eq_gains", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EqualizerPresets.BAND_COUNT } ?: EqualizerPresets.FLAT.gainsDb
        return EqualizerSettings(
            enabled = prefs.getBoolean("eq_enabled", false),
            presetName = prefs.getString("eq_preset", EqualizerPresets.FLAT.name)!!,
            gainsDb = gains,
            normalize = prefs.getBoolean("eq_normalize", true),
            bassBoostDb = prefs.getFloat("eq_bass", 0f),
        )
    }
}
