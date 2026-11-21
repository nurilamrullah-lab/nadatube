package com.nadatube.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.nadatube.app.extractor.ExtractionException
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.StreamQuality
import com.nadatube.app.extractor.StreamRangeInterceptor
import com.nadatube.app.extractor.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Collections

/** The real Media3 playback data-source chain (resolve → OkHttp → encrypted cache). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamDataSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val streamRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    // Same length as itag 251 in the fixture, so last-byte probes land inside the stream.
    private val audio = ByteArray(3_433_755) { (it * 31).toByte() }
    private var streamStatus = 206
    private lateinit var cache: SimpleCache

    @Before
    fun setUp() {
        val fixture = javaClass.getResource("/fixtures/player_android_vr.json")!!.readText()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/videoplayback")) {
                    streamRequests += request
                    if (streamStatus != 206) return MockResponse().setResponseCode(streamStatus)
                    val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(audio))
                    val (start, endRaw) = range.removePrefix("bytes=").split("-")
                    val s = start.toInt()
                    val e = if (endRaw.isEmpty()) audio.size - 1 else minOf(endRaw.toInt(), audio.size - 1)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $s-$e/${audio.size}")
                        .setBody(Buffer().write(audio.copyOfRange(s, e + 1)))
                }
                // Every client answers OK with URLs pointing back at this server.
                return MockResponse().setBody(
                    fixture.replace("https://rr1---sn-test.googlevideo.com/videoplayback", server.url("/videoplayback").toString())
                        .replace("expire=1700000000", "expire=9999999999"),
                )
            }
        }
        server.start()
        cache = SimpleCache(tmp.newFolder("cache"), NoOpCacheEvictor(), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
    }

    @After
    fun tearDown() {
        cache.release()
        server.shutdown()
    }

    private fun chain(http: OkHttpClient, resolver: StreamResolver, quality: StreamQuality = StreamQuality.BEST) =
        StreamDataSources.create(ApplicationProvider.getApplicationContext(), http, resolver, cache, ByteArray(16) { 7 }, { quality })

    private fun readAll(factory: androidx.media3.datasource.DataSource.Factory, videoId: String, position: Long = 0): ByteArray {
        val ds = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(MediaItems.streamUri(videoId)).setKey(videoId).setPosition(position).build()
        ds.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        ds.close()
        return out.toByteArray()
    }

    private fun localHttp() = OkHttpClient.Builder().addInterceptor(StreamRangeInterceptor { it.encodedPath == "/videoplayback" }).build()

    @Test
    fun `stream requests carry exactly one UA matching the issuing client, plus a Range`() {
        val http = localHttp()
        val resolver = StreamResolver(InnerTubeClient(http, baseUrl = server.url("/").toString().trimEnd('/')), { StreamQuality.BEST })
        val bytes = readAll(chain(http, resolver), "dQw4w9WgXcQ")
        assertArrayEquals(audio, bytes)

        val playback = streamRequests.last()
        assertEquals("one User-Agent header", 1, playback.headers.values("User-Agent").size)
        assertEquals(InnerTubeClient.VISIONOS_USER_AGENT, playback.getHeader("User-Agent"))
        assertTrue("Range always sent (avoids throttling)", playback.getHeader("Range")!!.startsWith("bytes=0-"))
        assertFalse("never ExoPlayer's default UA", playback.getHeader("User-Agent")!!.contains("ExoPlayer"))
    }

    @Test
    fun `seeking resumes with a ranged request and second play is served from the encrypted cache`() {
        val http = localHttp()
        val resolver = StreamResolver(InnerTubeClient(http, baseUrl = server.url("/").toString().trimEnd('/')), { StreamQuality.BEST })
        val factory = chain(http, resolver)
        assertArrayEquals(audio.copyOfRange(100_000, audio.size), readAll(factory, "seekvideo01", 100_000))
        assertEquals("bytes=100000-", streamRequests.last().getHeader("Range"))

        readAll(factory, "cachedvideo") // fills cache
        val before = streamRequests.size
        assertArrayEquals(audio, readAll(factory, "cachedvideo"))
        assertEquals("replay came from cache", before, streamRequests.size)
        val onDisk = tmp.root.walk().filter { it.isFile && it.name.endsWith(".exo") }.map { it.readBytes() }.toList()
        assertTrue(onDisk.isNotEmpty())
        assertTrue("cache is encrypted at rest", onDisk.none { it.size >= 64 && it.copyOfRange(0, 64).contentEquals(audio.copyOfRange(0, 64)) })
    }

    @Test
    fun `cache key separates qualities`() {
        val spec = DataSpec.Builder().setUri(MediaItems.streamUri("abc")).setKey("abc").build()
        val best = StreamDataSources.cacheKeyFactory { StreamQuality.BEST }.buildCacheKey(spec)
        val saver = StreamDataSources.cacheKeyFactory { StreamQuality.DATA_SAVER }.buildCacheKey(spec)
        assertNotEquals(best, saver)
        val file = DataSpec.Builder().setUri(Uri.parse("file:///x.m4a")).build()
        assertEquals("file:///x.m4a", StreamDataSources.cacheKeyFactory { StreamQuality.BEST }.buildCacheKey(file))
    }

    @Test
    fun `a 403 during playback is recognised and triggers reject-and-retry`() {
        val http = localHttp()
        val client = InnerTubeClient(http, baseUrl = server.url("/").toString().trimEnd('/'))
        val resolver = StreamResolver(client, { StreamQuality.BEST })
        runBlocking { resolver.resolve("forbidden01") } // probe passes while the server still says 206
        streamStatus = 403
        try {
            readAll(chain(http, resolver), "forbidden01")
            fail("expected 403")
        } catch (e: Exception) {
            assertEquals(403, PlaybackRecovery.httpCode(e))
            assertEquals(PlaybackRecovery.Action.REJECT_AND_RETRY, PlaybackRecovery.decide(403, 0, PlaybackRecovery.isExtractionFailure(e)))
        }
    }

    @Test
    fun `when extraction already failed, playback gives up with the extractor's message`() {
        streamStatus = 403 // every client's probe is refused
        val http = localHttp()
        val resolver = StreamResolver(InnerTubeClient(http, baseUrl = server.url("/").toString().trimEnd('/')), { StreamQuality.BEST })
        try {
            readAll(chain(http, resolver), "blocked0001")
            fail("expected failure")
        } catch (e: Exception) {
            assertTrue(PlaybackRecovery.isExtractionFailure(e))
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is ExtractionException && "memblokir" in it.message!! })
            assertEquals(PlaybackRecovery.Action.GIVE_UP, PlaybackRecovery.decide(null, 0, true))
        }
    }

    @Test
    fun `recovery policy`() {
        assertEquals(PlaybackRecovery.Action.REJECT_AND_RETRY, PlaybackRecovery.decide(410, 1))
        assertEquals(PlaybackRecovery.Action.RETRY, PlaybackRecovery.decide(503, 0))
        assertEquals(PlaybackRecovery.Action.RETRY, PlaybackRecovery.decide(null, 0))
        assertEquals(PlaybackRecovery.Action.GIVE_UP, PlaybackRecovery.decide(403, PlaybackRecovery.MAX_RETRIES))
        assertEquals(PlaybackRecovery.Action.GIVE_UP, PlaybackRecovery.decide(400, 0))
    }

    /** Opt-in: NADATUBE_LIVE=1. Plays real googlevideo bytes through the exact production chain. */
    @Test
    fun `live - full track through the production chain`() {
        assumeTrue(System.getenv("NADATUBE_LIVE") == "1")
        val app = OkHttpClient.Builder()
            .dns(com.nadatube.app.extractor.Ipv4FirstDns())
            .addInterceptor(StreamRangeInterceptor())
            .build()
        val client = InnerTubeClient(app)
        val resolver = StreamResolver(client, { StreamQuality.BEST })
        val start = System.currentTimeMillis()
        val bytes = readAll(chain(app, resolver), "dQw4w9WgXcQ")
        val resolved = runBlocking { resolver.resolve("dQw4w9WgXcQ") }
        val stream = resolved.streams.first { it.itag == 251 }
        println("live chain → client=${resolved.clientName} bytes=${bytes.size}/${stream.contentLength} in ${System.currentTimeMillis() - start} ms")
        assertEquals(stream.contentLength, bytes.size.toLong())
        assertArrayEquals("WebM/EBML header", byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), bytes.copyOfRange(0, 4))
    }
}
