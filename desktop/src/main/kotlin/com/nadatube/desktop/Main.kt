package com.nadatube.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nadatube.app.model.Track
import com.nadatube.app.ui.theme.ThemeMode
import com.nadatube.desktop.audio.DesktopPlayer
import com.nadatube.desktop.audio.NullAudioSink
import com.nadatube.desktop.data.DesktopServices
import com.nadatube.desktop.ui.DesktopApp
import com.nadatube.desktop.ui.NadaTubeTheme
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Entry point aplikasi desktop NadaTube (Windows/macOS/Linux).
 *
 * Mode:
 * - tanpa argumen: buka jendela aplikasi.
 * - `--smoke`: buka jendela, keluar sendiri setelah 5 detik (verifikasi headless di CI/VM).
 * - `--smoke-decode <file.m4a>`: tanpa jendela — demux + dekode file AAC lewat
 *   [DesktopPlayer] dengan [NullAudioSink], lalu cetak jumlah frame/durasi dan keluar.
 *   Dipakai untuk membuktikan jalur audio bekerja di mesin tanpa perangkat audio.
 */
fun main(args: Array<String>) {
    if (args.contains("--smoke-decode")) {
        val path = args.getOrNull(args.indexOf("--smoke-decode") + 1)
            ?: error("penggunaan: --smoke-decode <file.m4a>")
        smokeDecode(File(path))
        return
    }
    if (args.contains("--smoke")) {
        Thread {
            Thread.sleep(5000)
            println("SMOKE OK — window opened, exiting")
            kotlin.system.exitProcess(0)
        }.apply { isDaemon = true; start() }
    }
    runApp()
}

private fun smokeDecode(file: File) {
    val services = DesktopServices()
    val player = DesktopPlayer(
        resolver = services.resolver,
        http = services.http,
        sponsorBlock = services.sponsorBlock,
        store = services.store,
        cacheDir = services.store.cacheDir(),
        sinkFactory = { NullAudioSink() },
    )
    val track = Track("smoke", file.nameWithoutExtension, "smoke", null, 0, null)
    runBlocking {
        player.playFromBytes(track, file.readBytes())
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val s = player.state.value
            if (s.status == com.nadatube.desktop.audio.PlaybackStatus.STOPPED) break
            if (s.status == com.nadatube.desktop.audio.PlaybackStatus.ERROR) {
                error("smoke-decode gagal: ${s.error}")
            }
            kotlinx.coroutines.delay(100)
        }
        val s = player.state.value
        println("SMOKE-DECODE OK file=${file.name} durationMs=${s.durationMs} frames=see-log")
        if (s.status != com.nadatube.desktop.audio.PlaybackStatus.STOPPED) {
            error("smoke-decode timeout, status=${s.status}")
        }
    }
}

private fun runApp() {
    val services = DesktopServices()
    application {
        val dark = when (services.store.themeMode()) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        val windowState = rememberWindowState(size = DpSize(1100.dp, 700.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "NadaTube — YouTube Audio-Only Player",
        ) {
            NadaTubeTheme(dark = dark) {
                DesktopApp(services)
            }
        }
    }
}
