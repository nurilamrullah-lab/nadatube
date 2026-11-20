package com.nadatube.app.sponsorblock

import com.nadatube.app.model.SponsorSegment
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorSkipLogicTest {
    private fun seg(id: String, start: Long, end: Long, cat: String = "sponsor", action: String = "skip") =
        SponsorSegment(id, cat, start, end, action)

    @Test
    fun `skips when inside a segment`() {
        val s = seg("a", 10_000, 20_000)
        assertEquals("a", SponsorSkipLogic.segmentToSkip(12_000, listOf(s), emptySet())?.uuid)
        assertEquals("a", SponsorSkipLogic.segmentToSkip(10_000, listOf(s), emptySet())?.uuid)
    }

    @Test
    fun `does not skip outside or right at the end of a segment`() {
        val s = seg("a", 10_000, 20_000)
        assertNull(SponsorSkipLogic.segmentToSkip(9_999, listOf(s), emptySet()))
        assertNull(SponsorSkipLogic.segmentToSkip(19_800, listOf(s), emptySet()))
        assertNull(SponsorSkipLogic.segmentToSkip(25_000, listOf(s), emptySet()))
    }

    @Test
    fun `respects manual seek back into an already skipped segment`() {
        val s = seg("a", 10_000, 20_000)
        assertNull(SponsorSkipLogic.segmentToSkip(15_000, listOf(s), setOf("a")))
    }

    @Test
    fun `ignores disabled categories, non-skip actions and tiny segments`() {
        val list = listOf(
            seg("intro", 0, 5_000, cat = "intro"),
            seg("mute", 6_000, 9_000, action = "mute"),
            seg("tiny", 30_000, 30_500),
        )
        assertNull(SponsorSkipLogic.segmentToSkip(1_000, list, emptySet(), enabledCategories = setOf("sponsor")))
        assertNull(SponsorSkipLogic.segmentToSkip(7_000, list, emptySet()))
        assertNull(SponsorSkipLogic.segmentToSkip(30_100, list, emptySet()))
    }

    @Test
    fun `overlapping segments merge into one jump`() {
        val merged = SponsorSkipLogic.merge(listOf(seg("b", 15_000, 30_000), seg("a", 10_000, 20_000), seg("c", 30_100, 35_000)))
        assertEquals(1, merged.size)
        assertEquals(10_000L, merged[0].startMs)
        assertEquals(35_000L, merged[0].endMs)
        assertEquals(35_000L, SponsorSkipLogic.segmentToSkip(11_000, listOf(seg("a", 10_000, 20_000), seg("b", 15_000, 35_000)), emptySet())!!.endMs)
    }

    @Test
    fun `outro reaching the end means skip to next track`() {
        assertNull(SponsorSkipLogic.seekTarget(seg("o", 200_000, 239_900, "outro"), 240_000))
        assertEquals(20_000L, SponsorSkipLogic.seekTarget(seg("a", 10_000, 20_000), 240_000))
    }

    @Test
    fun `total skippable time`() {
        assertEquals(15_000L, SponsorSkipLogic.totalSkippableMs(listOf(seg("a", 0, 10_000, "intro"), seg("b", 50_000, 55_000))))
    }

    @Test
    fun `client parses api response, sorts and caches`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """[{"segment":[120.5,140.25],"UUID":"u2","category":"sponsor","actionType":"skip","votes":3},
                    {"segment":[0,12],"UUID":"u1","category":"intro","actionType":"skip"}]""",
            ),
        )
        server.start()
        val client = SponsorBlockClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        val segments = client.segments("abc")
        assertEquals(listOf("u1", "u2"), segments.map { it.uuid })
        assertEquals(120_500L, segments[1].startMs)
        assertEquals(140_250L, segments[1].endMs)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/skipSegments?videoID=abc&categories="))
        client.segments("abc") // cached: no second request
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `client treats 404 as no segments`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.start()
        val client = SponsorBlockClient(OkHttpClient(), server.url("/").toString().trimEnd('/'))
        assertTrue(client.segments("xyz").isEmpty())
        server.shutdown()
    }
}
