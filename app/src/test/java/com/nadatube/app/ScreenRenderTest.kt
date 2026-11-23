package com.nadatube.app

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.nadatube.app.data.db.DownloadEntity
import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.AudioStream
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.model.SponsorSegment
import com.nadatube.app.playback.PlayerUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Renders the real screens (with live YouTube data) to PNGs in app/screenshots/.
 * Opt-in: NADATUBE_SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*ScreenRenderTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenRenderTest {
    // No real media session under Robolectric: make binding fail cleanly before launch.
    @get:Rule(order = 0) val noSessionBinding = object : org.junit.rules.ExternalResource() {
        override fun before() {
            org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
                .declareActionUnbindable("androidx.media3.session.MediaSessionService")
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<NadaTubeApp>().container

    @Before
    fun gate() = assumeTrue(System.getenv("NADATUBE_SCREENSHOTS") == "1")

    private fun waitFor(text: String, timeout: Long = 30_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun settle(ms: Long = 4_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            Thread.sleep(200)
        }
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    @Test
    fun renderScreens() {
        val songs = runBlocking { container.innerTube.search("coldplay", SearchFilter.SONGS).items.filterIsInstance<SearchItem.Song>().map { it.track } }
        val now = songs[4]
        runBlocking {
            songs.take(6).reversed().forEach { container.library.recordPlay(it) }
            songs.take(4).forEach { container.library.setFavorite(it, true) }
            val pl = container.library.createPlaylist("Perjalanan Malam")
            songs.drop(2).take(5).forEach { container.library.addToPlaylist(pl, it) }
            container.library.createPlaylist("Fokus Kerja").let { id -> songs.drop(5).take(3).forEach { container.library.addToPlaylist(id, it) } }
            songs.take(3).forEachIndexed { i, t ->
                container.database.downloads().upsert(
                    DownloadEntity(
                        t.videoId, status = DownloadStatus.COMPLETED.name, progress = 1f, filePath = "/sdcard/x$i.m4a",
                        mimeType = "audio/mp4", bitrateKbps = 128, sizeBytes = 4_200_000L + i * 900_000, createdAt = 10L - i,
                    ),
                )
            }
            container.database.downloads().upsert(
                DownloadEntity(songs[3].videoId, status = DownloadStatus.DOWNLOADING.name, progress = 0.42f, createdAt = 20),
            )
        }
        container.streamResolver.markSelected(
            now.videoId,
            AudioStream(251, "", "audio/webm", AudioCodec.OPUS, 136_000, 128_000, 48_000, 2, null),
        )
        container.nowPlaying.segments.value = listOf(
            SponsorSegment("i", "intro", 0, 9_000), SponsorSegment("s", "music_offtopic", 210_000, 228_000),
        )
        compose.runOnUiThread {
            container.player.overrideStateForTest(
                PlayerUiState(track = now, isPlaying = true, positionMs = 74_000, bufferedMs = 120_000, durationMs = now.durationMs, queue = songs),
            )
        }
        feedVisualizer()

        waitFor("dan lainnya")
        settle()
        shot("1_home")

        compose.onNode(hasText("Cari")).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("coldplay")
        waitFor("HQ", 30_000)
        settle()
        shot("2_search")

        compose.onAllNodes(hasText(songs[1].title))[0].performClick()
        // the first row tap starts playback; reopen with our fake state via the mini player
        compose.runOnUiThread {
            container.player.overrideStateForTest(
                PlayerUiState(track = now, isPlaying = true, positionMs = 74_000, bufferedMs = 120_000, durationMs = now.durationMs, queue = songs),
            )
        }
        compose.activityRule.scenario.onActivity { it.openPlayer.value = true }
        waitFor("SEDANG DIPUTAR")
        settle()
        shot("3_player")

        compose.onNode(hasText("EQ")).performClick()
        waitFor("Equalizer 10-band")
        settle(1_500)
        com.github.takahirom.roborazzi.captureScreenRoboImage("screenshots/4_equalizer.png")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle(800)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle(800)

        compose.onNode(hasText("Pustaka")).performClick()
        waitFor("Penyimpanan")
        settle()
        shot("5_library")
    }

    private fun feedVisualizer() {
        container.visualizer.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        repeat(48) { n ->
            val amp = 0.15 + 0.6 * (0.5 + 0.5 * sin(n / 4.0))
            val buf = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder())
            for (i in 0 until 2048) buf.putShort((Short.MAX_VALUE * amp * sin(i / 9.0)).toInt().toShort())
            buf.flip()
            container.visualizer.handleBuffer(buf)
            Thread.sleep(42)
        }
    }
}
