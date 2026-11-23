package com.nadatube.app.ui.theme

/**
 * Pilihan tema aplikasi, dipakai bersama oleh Android (:app) dan Windows (:desktop).
 * Disimpan sebagai nama enum (LIGHT/DARK/SYSTEM) di pengaturan masing-masing platform.
 */
enum class ThemeMode(val label: String, val description: String) {
    LIGHT("Terang (Light)", "Kanvas bersih, kartu putih"),
    DARK("Gelap (Dark)", "Nyaman untuk memutar musik malam hari"),
    SYSTEM("Ikuti Sistem (System Default)", "Mengikuti pengaturan perangkat");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun of(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
