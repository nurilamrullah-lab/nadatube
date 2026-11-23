package com.nadatube.desktop.audio

import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.extractor.StreamResolver
import com.nadatube.app.model.Track
import com.nadatube.app.sponsorblock.SponsorBlockClient
import com.nadatube.desktop.data.DesktopStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end player test melawan MockWebServer — cermin dari
 * `DesktopPlayerTest` yang dikerjakan Hoplite saat thread-nya gagal:
 * AAC itag 140 diunduh, 88 frame, durasi ~2023 ms, riwayat berisi videoId,
 * file cache `aaaaaaaaaaa_140` ada, dan visitorData `VD1` terkirim di body
 * request player.
 */
class DesktopPlayerTest {

    private lateinit var server: MockWebServer
    private lateinit var tmpDir: File
    private lateinit var store: DesktopStore
    private lateinit var player: DesktopPlayer
    private val sinks = java.util.Collections.synchronizedList(mutableListOf<FakeAudioSink>())
    private val playerRequestBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var fixtureBytes: ByteArray

    private val track = Track("aaaaaaaaaaa", "Tone Test", "Test", null, 2023, null)

    @Before
    fun setUp() {
        fixtureBytes = javaClass.classLoader.getResourceAsStream("tone-440-aac-frag.m4a")!!.readBytes()
        tmpDir = Files.createTempDirectory("nadatube-test").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/youtubei/v1/visitor_id") ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"responseContext":{"visitorData":"VD1"}}""")
                    path.startsWith("/youtubei/v1/player") -> {
                        playerRequestBodies += request.body.readUtf8()
                        MockResponse().setResponseCode(200).setBody(playerJson())
                    }
                    path.startsWith("/stream/") ->
                        MockResponse().setResponseCode(206)
                            .setBody(Buffer().write(fixtureBytes))
                    else -> MockResponse().setResponseCode(200).setBody("[]")
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        store = DesktopStore(File(tmpDir, "config"))
        val http = OkHttpClient.Builder().build()
        val client = InnerTubeClient(
            http = http,
            baseUrl = base,
            musicBaseUrl = base,
            initialVisitorData = null,
            onVisitorData = { store.saveVisitorData(it) },
        )
        val resolver = StreamResolver(client, qualityProvider = { StreamQuality.BEST })
        player = DesktopPlayer(
            resolver = resolver,
            http = http,
            sponsorBlock = SponsorBlockClient(http, base),
            store = store,
            cacheDir = File(tmpDir, "cache"),
            sinkFactory = { FakeAudioSink().also { sinks += it } },
        )
    }

    @After
    fun tearDown() {
        runCatching { player.stop() }
        runCatching { server.shutdown() }
        runCatching { tmpDir.deleteRecursively() }
    }

    private fun playerJson(): String {
        val streamUrl = server.url("/stream/aaaaaaaaaaa_140?expire=9999999999").toString()
        return """
        {
          "playabilityStatus": {"status": "OK"},
          "videoDetails": {
            "videoId": "aaaaaaaaaaa",
            "title": "Tone Test",
            "author": "Test",
            "lengthSeconds": "2"
          },
          "streamingData": {
            "expiresInSeconds": "21600",
            "adaptiveFormats": [
              {
                "itag": 140,
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 131007,
                "averageBitrate": 131007,
                "audioSampleRate": "44100",
                "audioChannels": 2,
                "contentLength": "${fixtureBytes.size}",
                "url": "$streamUrl"
              }
            ]
          }
        }
        """.trimIndent()
    }

    @Test
    fun `AAC itag 140 plays end to end`() = runBlocking {
        player.play(track)
        awaitStatus(PlaybackStatus.STOPPED)

        // Asersi yang sama dengan yang dikerjakan Hoplite di DesktopPlayerTest:
        assertEquals("aaaaaaaaaaa", store.history().firstOrNull()?.videoId)
        val cacheFile = File(tmpDir, "cache/aaaaaaaaaaa_140.m4a")
        assertTrue("cache file ada: $cacheFile", cacheFile.exists())
        assertEquals(fixtureBytes.size.toLong(), cacheFile.length())
        assertTrue("visitorData VD1 terkirim di body player", playerRequestBodies.any { "VD1" in it })
        assertEquals("VD1", store.visitorData())

        val sink = sinks.single()
        assertEquals(88, sink.framesWritten)
        assertEquals(88L * 1024 * 2 * 2, sink.bytesWritten)
        assertTrue("durationMs=${player.state.value.durationMs}", player.state.value.durationMs in 2000..2050)
    }

    @Test
    fun `playFromBytes decodes all frames without network`() = runBlocking {
        player.playFromBytes(track, fixtureBytes)
        awaitStatus(PlaybackStatus.STOPPED)
        val sink = sinks.single()
        assertEquals(88, sink.framesWritten)
        assertEquals(88L * 1024 * 2 * 2, sink.bytesWritten)
    }

    @Test
    fun `pause and resume keep the frame count`() = runBlocking {
        player.play(track)
        // Beri waktu loop decode berjalan, lalu pause — sink palsu tidak memblokir,
        // jadi jeda bisa terjadi setelah semua frame tertulis; yang penting tidak error
        // dan status akhir tetap STOPPED dengan 88 frame.
        delay(50)
        player.pause()
        player.resume()
        awaitStatus(PlaybackStatus.STOPPED)
        assertEquals(88, sinks.single().framesWritten)
    }

    private suspend fun awaitStatus(vararg want: PlaybackStatus) {
        withTimeout(15_000) {
            while (player.state.value.status !in want) delay(25)
        }
    }
}
