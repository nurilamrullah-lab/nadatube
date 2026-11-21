package com.nadatube.desktop

import com.nadatube.app.model.Track
import com.nadatube.desktop.audio.AudioCache
import com.nadatube.desktop.audio.PcmSink
import com.nadatube.desktop.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole desktop pipeline against a fake YouTube/googlevideo/SponsorBlock server. */
class DesktopPlayerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val tone = DesktopPlayerTest::class.java.getResourceAsStream("/audio/tone440_fragmented.m4a")!!.readBytes()
    private val playerFixture = DesktopPlayerTest::class.java.getResourceAsStream("/fixtures/player_android_vr.json")!!.readBytes().decodeToString()
    private val downloads = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val playerCalls = Collections.synchronizedList(mutableListOf<String>())
    private var botGated = false
    private var segmentsJson = "[]"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sinks = Collections.synchronizedList(mutableListOf<FakeSink>())
    @Volatile private var frameDelayMs = 0L

    private lateinit var services: Services

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/youtubei/v1/visitor_id") -> MockResponse().setBody("""{"responseContext":{"visitorData":"VD1"}}""")
                    path.startsWith("/youtubei/v1/player") -> {
                        playerCalls += request.body.readUtf8()
                        if (botGated) MockResponse().setBody("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""")
                        else MockResponse().setBody(
                            playerFixture.replace("https://rr1---sn-test.googlevideo.com/videoplayback", server.url("/videoplayback").toString())
                                .replace("expire=1700000000", "expire=9999999999")
                                .replace("\"3449447\"", "\"${tone.size}\""),
                        )
                    }
                    path.startsWith("/videoplayback") -> {
                        downloads += request
                        val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val body = if (start >= tone.size) ByteArray(1) else tone.copyOfRange(start, tone.size)
                        MockResponse().setResponseCode(206).setBody(Buffer().write(body))
                    }
                    path.startsWith("/api/skipSegments") -> MockResponse().setBody(segmentsJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        services = Services(
            dataDir = tmp.newFolder("data"), baseUrl = base, musicBaseUrl = base, sponsorBaseUrl = base, lyricsBaseUrl = base,
            sinkFactory = { r, c ->
                val fake = FakeSink(r, c).also { sinks += it }
                object : PcmSink by fake { override fun write(pcm: ByteArray) { fake.write(pcm); if (frameDelayMs > 0) Thread.sleep(frameDelayMs) } }
            },
        )
    }

    @After
    fun tearDown() {
        services.engine.stop()
        scope.cancel()
        server.shutdown()
    }

    private fun track(id: String) = Track(id, "Title $id", "Artist", null, 2_000, null)
    private fun waitUntil(ms: Long = 8_000, what: String = "condition", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(10) }
    }
    private fun framesWritten() = synchronized(sinks) { sinks.sumOf { it.bytes.size() } / 4096 }

    @Test
    fun `plays a track end to end, records history and caches the audio`() {
        val player = DesktopPlayer(services, scope)
        player.play(track("aaaaaaaaaaa"))
        waitUntil(what = "playback") { player.state.value.status == Status.PLAYING && framesWritten() > 0 }
        assertEquals("aaaaaaaaaaa", player.state.value.track?.videoId)
        waitUntil(what = "end of track") { player.state.value.status == Status.PAUSED }

        assertTrue(abs(player.state.value.durationMs - 2_023) < 40, "duration from the file, not the metadata: ${player.state.value.durationMs}")
        assertEquals(88, framesWritten().toInt())
        assertEquals(listOf("aaaaaaaaaaa"), services.store.state.value.history.map { it.track.videoId })
        assertTrue(File(tmp.root, "data/cache").listFiles()!!.any { it.name.startsWith("aaaaaaaaaaa_140") && it.length() == tone.size.toLong() })
        val itag = Regex("itag=(\\d+)").find(downloads.first { it.getHeader("Range") == "bytes=0-" }.path!!)!!.groupValues[1].toInt()
        assertEquals(140, itag, "AAC rendition (itag 140) was downloaded, not Opus")
        val body = playerCalls.first()
        assertTrue("visitorData\":\"VD1\"" in body, "visitor id from /visitor_id is sent: $body")
    }

    @Test
    fun `second play comes from the cache without downloading again`() {
        val player = DesktopPlayer(services, scope)
        player.play(track("bbbbbbbbbbb"))
        waitUntil(what = "first end") { player.state.value.status == Status.PAUSED }
        val fullDownloads = { downloads.count { it.getHeader("Range") == "bytes=0-" } }
        assertEquals(1, fullDownloads())

        val before = framesWritten()
        player.play(track("bbbbbbbbbbb"))
        waitUntil(what = "second end") { framesWritten() >= before + 88 }
        assertEquals(1, fullDownloads(), "served from disk")
    }

    @Test
    fun `plays the queue in order and stops after the last track`() {
        val player = DesktopPlayer(services, scope)
        player.play(listOf(track("ccccccccccc"), track("ddddddddddd")))
        waitUntil(what = "second track") { player.state.value.index == 1 && framesWritten() > 88 }
        waitUntil(what = "end of queue") { framesWritten().toInt() == 176 && player.state.value.status == Status.PAUSED }
        assertEquals(listOf("ddddddddddd", "ccccccccccc"), services.store.state.value.history.map { it.track.videoId })
        assertEquals(1, player.state.value.index, "does not wrap without repeat")
    }

    @Test
    fun `repeat one replays the same track`() {
        val player = DesktopPlayer(services, scope)
        player.cycleRepeat(); player.cycleRepeat()
        assertEquals(RepeatMode.ONE, player.state.value.repeat)
        player.play(track("eeeeeeeeeee"))
        waitUntil(what = "replay") { framesWritten() >= 176 }
        assertEquals(0, player.state.value.index)
        player.togglePlay()
    }

    @Test
    fun `sponsorblock segment is skipped during playback`() {
        segmentsJson = """[{"segment":[0.5,1.5],"UUID":"seg1","category":"sponsor","actionType":"skip"}]"""
        frameDelayMs = 25
        val player = DesktopPlayer(services, scope)
        player.play(track("fffffffffff"))
        waitUntil(what = "skip") { player.state.value.lastSkipped != null }
        assertEquals("seg1", player.state.value.lastSkipped?.uuid)
        waitUntil(what = "finish") { player.state.value.status == Status.PAUSED }
        // ~1 s of 2 s was skipped, so well under the full 88 frames were played.
        assertTrue(framesWritten() < 70, "frames played ${framesWritten()}")
    }

    @Test
    fun `bot-gated song shows a clear message and does not crash`() {
        botGated = true
        val player = DesktopPlayer(services, scope)
        player.play(track("ggggggggggg"))
        waitUntil(what = "error") { player.state.value.error != null }
        assertEquals(Status.PAUSED, player.state.value.status)
        assertTrue("memblokir" in player.state.value.error!!, player.state.value.error!!)
        assertEquals(0, framesWritten().toInt())
        assertTrue(services.store.state.value.history.isEmpty())
    }

    @Test
    fun `volume is stored and applied`() {
        val player = DesktopPlayer(services, scope)
        player.setVolume(0.4f)
        assertEquals(0.4f, services.engine.volume)
        assertEquals(0.4f, services.store.state.value.volume)
        player.setVolume(7f)
        assertEquals(1f, player.state.value.volume)
    }

    private fun abs(x: Long) = kotlin.math.abs(x)
}

class StoreAndCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `favorites, history and settings persist across restarts`() {
        val file = File(tmp.root, "state.json")
        var now = 1_000L
        val a = Store(file) { now++ }
        val t1 = Track("11111111111", "One", "A", "Album", 1000, "http://x/1")
        val t2 = Track("22222222222", "Two", "B")
        a.toggleFavorite(t1); a.toggleFavorite(t2); a.toggleFavorite(t1)
        assertEquals(listOf("22222222222"), a.state.value.favorites.map { it.videoId })
        assertTrue(a.isFavorite("22222222222") && !a.isFavorite("11111111111"))
        a.recordPlay(t1); a.recordPlay(t1); a.recordPlay(t2)
        a.update { it.copy(themeMode = "DARK", volume = 0.3f, visitorData = "VD") }

        val b = Store(file)
        assertEquals(listOf("22222222222"), b.state.value.favorites.map { it.videoId })
        assertEquals(listOf("22222222222", "11111111111"), b.state.value.history.map { it.track.videoId }, "repeat of the same song is not duplicated")
        assertEquals("DARK", b.state.value.themeMode)
        assertEquals(0.3f, b.state.value.volume)
        assertEquals("VD", b.state.value.visitorData)
        assertEquals(t2, b.state.value.favorites.single().toTrack())
        b.clearHistory()
        assertTrue(Store(file).state.value.history.isEmpty())
    }

    @Test
    fun `history is capped and a corrupt file is set aside instead of crashing`() {
        val file = File(tmp.root, "s.json")
        val s = Store(file)
        repeat(Store.MAX_HISTORY + 25) { s.recordPlay(Track("%011d".format(it), "T$it", "A")) }
        assertEquals(Store.MAX_HISTORY, s.state.value.history.size)
        assertEquals("%011d".format(Store.MAX_HISTORY + 24), s.state.value.history.first().track.videoId)

        file.writeText("{ this is not json")
        val recovered = Store(file)
        assertTrue(recovered.state.value.history.isEmpty())
        assertTrue(File(tmp.root, "s.json.corrupt").exists())
        recovered.update { it.copy(volume = 0.5f) }
        assertEquals(0.5f, Store(file).state.value.volume)
    }

    @Test
    fun `cache evicts least recently used and keeps the newest`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(1000) { 1 }))
        }
        server.start()
        try {
            val cache = AudioCache(File(tmp.root, "c"), maxBytes = 2_500, http = OkHttpClient())
            fun stream(id: String, len: Long?) = com.nadatube.app.model.AudioStream(140, server.url("/$id").toString(), "audio/mp4", com.nadatube.app.model.AudioCodec.AAC, 128000, null, 44100, 2, len)
            kotlinx.coroutines.runBlocking {
                val f1 = cache.fetch("v1", stream("v1", 1000), "UA"); f1.setLastModified(1_000)
                val f2 = cache.fetch("v2", stream("v2", 1000), "UA"); f2.setLastModified(2_000)
                cache.cached("v1") // touch: v1 is now the most recent
                cache.fetch("v3", stream("v3", 1000), "UA")
            }
            assertTrue(cache.cached("v1") != null && cache.cached("v3") != null)
            assertTrue(cache.cached("v2") == null, "v2 was least recently used")
            assertTrue(cache.sizeBytes() <= 2_500)
            assertEquals("UA", server.takeRequest().getHeader("User-Agent"))
        } finally { server.shutdown() }
    }

    @Test
    fun `incomplete download is rejected and leaves no file`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(400))))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val cache = AudioCache(File(tmp.root, "d"), http = OkHttpClient())
            val s = com.nadatube.app.model.AudioStream(140, server.url("/x").toString(), "audio/mp4", com.nadatube.app.model.AudioCodec.AAC, 128000, null, 44100, 2, 1000)
            val e = kotlin.test.assertFailsWith<java.io.IOException> { kotlinx.coroutines.runBlocking { cache.fetch("short", s, "UA") } }
            assertTrue("tidak lengkap" in e.message!!)
            assertTrue(cache.cached("short") == null)
            kotlin.test.assertFailsWith<com.nadatube.app.extractor.StreamRejectedException> { kotlinx.coroutines.runBlocking { cache.fetch("denied", s, "UA", "IOS/1") } }
            assertTrue(File(tmp.root, "d").listFiles()!!.none { it.name.endsWith(".m4a") })
        } finally { server.shutdown() }
    }
}
