package com.nadatube.app.extractor

import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Client fallback chain against a fake InnerTube + googlevideo. */
class StreamClientFallbackTest {
    private val server = MockWebServer()
    private val fixture = javaClass.getResource("/fixtures/player_android_vr.json")!!.readText()

    /** clientName -> how /player answers ("bot", "ok-403", "ok-206", "unavailable"). */
    private val behaviour = mutableMapOf<String, String>()
    private val probes = mutableListOf<RecordedRequest>()

    private fun okBody(streamPath: String) =
        fixture.replace("https://rr1---sn-test.googlevideo.com/videoplayback", server.url(streamPath).toString())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/stream/")) {
                    probes += request
                    if (path.startsWith("/stream/bad")) return MockResponse().setResponseCode(403)
                    if (path.startsWith("/stream/firstmb")) {
                        // Real googlevideo behaviour for PO-token-less URLs: ranges ending
                        // inside the first ~1 MB are served, anything else is 403.
                        val end = request.getHeader("Range")?.substringAfter('-')?.toLongOrNull()
                        return if (end != null && end < 1_048_576) MockResponse().setResponseCode(206).setBody("x")
                        else MockResponse().setResponseCode(403)
                    }
                    return MockResponse().setResponseCode(206).setBody("x")
                }
                val body = request.body.readUtf8()
                val name = Regex("\"clientName\":\"([A-Z_]+)\"").find(body)!!.groupValues[1]
                return when (behaviour[name]) {
                    "bot" -> MockResponse().setBody("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""")
                    "unavailable" -> MockResponse().setBody("""{"playabilityStatus":{"status":"ERROR","reason":"Video unavailable"}}""")
                    "ok-403" -> MockResponse().setBody(okBody("/stream/bad/$name"))
                    "ok-firstmb" -> MockResponse().setBody(okBody("/stream/firstmb/$name"))
                    "ok-206" -> MockResponse().setBody(okBody("/stream/good/$name"))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = InnerTubeClient(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `skips bot-gated and 403 clients, picks the first whose stream really downloads`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "bot", "ANDROID" to "ok-403", "IOS" to "ok-206")
        val resolved = client().player("dQw4w9WgXcQ")
        assertEquals("IOS/20.10.4", resolved.clientName)
        assertEquals(InnerTubeClient.IOS.userAgent, resolved.userAgent)
        assertTrue(resolved.streams.all { "/stream/good/IOS" in it.url })

        val iosProbe = probes.last()
        assertEquals("probe the last byte of the 3433755-byte stream", "bytes=3433754-3433754", iosProbe.getHeader("Range"))
        assertEquals("probe must use the issuing client's UA", InnerTubeClient.IOS.userAgent, iosProbe.getHeader("User-Agent"))
        assertTrue("ANDROID's 403 URL was probed and rejected", probes.any { "/stream/bad/ANDROID" in it.path!! })
    }

    @Test
    fun `urls that only serve the first megabyte are rejected (the 403-after-start bug)`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "bot", "ANDROID" to "ok-firstmb", "IOS" to "ok-206")
        assertEquals("IOS/20.10.4", client().player("9qnqYL0eNNI").clientName)
        assertTrue(probes.any { "/stream/firstmb/ANDROID" in it.path!! })

        behaviour["IOS"] = "ok-firstmb"
        val e = assertThrows(ExtractionException::class.java) { kotlinx.coroutines.runBlocking { client().player("kJQP7kiw5Fk") } }
        assertTrue(e.message!!.contains("memblokir"))
    }

    @Test
    fun `everything blocked gives an actionable message instead of a 403`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "bot", "ANDROID" to "ok-403", "IOS" to "ok-403")
        val e = assertThrows(ExtractionException::class.java) { kotlinx.coroutines.runBlocking { client().player("dQw4w9WgXcQ") } }
        assertTrue(e.message, e.message!!.contains("memblokir"))
        assertTrue(e.message!!.contains("VPN"))
    }

    @Test
    fun `genuinely unavailable video surfaces YouTube's own reason`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "unavailable", "ANDROID" to "unavailable", "IOS" to "unavailable")
        val e = assertThrows(PlayabilityException::class.java) { kotlinx.coroutines.runBlocking { client().player("dQw4w9WgXcQ") } }
        assertEquals("Video unavailable", e.reason)
        assertFalse(e.isBotCheck)
    }

    @Test
    fun `a client reported as rejected mid-playback is skipped next time`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "ok-206", "ANDROID" to "ok-206", "IOS" to "ok-206")
        val c = client()
        assertEquals("ANDROID_VR/1.57.29", c.player("abc").clientName)
        c.reportRejected("abc", "ANDROID_VR/1.57.29")
        assertEquals("ANDROID_VR/1.62.27", c.player("abc").clientName)
        // Other videos are unaffected.
        assertEquals("ANDROID_VR/1.57.29", c.player("other").clientName)
    }

    @Test
    fun `when every client was rejected the memory resets instead of failing forever`() = runTest {
        behaviour += mapOf("ANDROID_VR" to "ok-206", "ANDROID" to "ok-206", "IOS" to "ok-206")
        val c = client()
        InnerTubeClient.STREAM_CLIENTS.forEach { c.reportRejected("abc", "${it.name}/${it.version}") }
        assertEquals("ANDROID_VR/1.57.29", c.player("abc").clientName)
    }

    @Test
    fun `range interceptor adds an open range only to stream hosts without one`() {
        val http = OkHttpClient.Builder().addInterceptor(StreamRangeInterceptor { !it.encodedPath.endsWith("/other") }).build()
        fun call(path: String, range: String? = null) = http.newCall(
            okhttp3.Request.Builder().url(server.url(path)).apply { if (range != null) header("Range", range) }.build(),
        ).execute().close()

        call("/stream/good/a")
        call("/stream/good/b", "bytes=100-199")
        call("/stream/good/other")
        assertEquals("bytes=0-", probes[0].getHeader("Range"))
        assertNull("no gzip negotiated on audio", probes[0].getHeader("Accept-Encoding")?.takeIf { "gzip" in it })
        assertEquals("existing range kept", "bytes=100-199", probes[1].getHeader("Range"))
        assertNull("non-stream hosts untouched", probes[2].getHeader("Range"))
    }

    @Test
    fun `stream host matcher`() {
        assertTrue(StreamHosts.isStream(okhttp3.HttpUrl.Builder().scheme("https").host("rr3---sn-npoe7ns7.googlevideo.com").build()))
        assertFalse(StreamHosts.isStream(okhttp3.HttpUrl.Builder().scheme("https").host("www.youtube.com").build()))
        assertFalse(StreamHosts.isStream(okhttp3.HttpUrl.Builder().scheme("https").host("evilgooglevideo.com").build()))
    }

    @Test
    fun `dns prefers ipv4 so player and stream requests share an address family`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val v4 = InetAddress.getByName("192.0.2.1")
        fun dns(vararg a: InetAddress) = object : Dns { override fun lookup(hostname: String) = a.toList() }
        assertEquals(listOf(v4), Ipv4FirstDns(dns(v6, v4)).lookup("x"))
        assertEquals("IPv6-only networks still work", listOf(v6), Ipv4FirstDns(dns(v6)).lookup("x"))
    }
}
