package com.nadatube.app

import com.nadatube.app.extractor.AudioStreamSelector
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.lyrics.LyricsClient
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import com.nadatube.app.sponsorblock.SponsorBlockClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real services. Opt-in: NADATUBE_LIVE=1 ./gradlew testDebugUnitTest */
class LiveSmokeTest {
    private val http = OkHttpClient()

    @Test
    fun `end to end against live services`() = runBlocking {
        assumeTrue(System.getenv("NADATUBE_LIVE") == "1")
        val yt = InnerTubeClient(http)

        val songs = yt.search("coldplay yellow", SearchFilter.SONGS).items.filterIsInstance<SearchItem.Song>()
        assertTrue(songs.isNotEmpty())
        val song = songs.first().track
        println("search → ${song.title} / ${song.artist} / ${song.durationMs}")
        assertTrue(song.durationMs > 0)

        // Music "art tracks" are often bot-gated from datacenter IPs (CI/sandbox), so the
        // stream check uses a regular music video that datacenter IPs can still fetch.
        val resolved = yt.player("dQw4w9WgXcQ")
        val stream = AudioStreamSelector.selectForPlayback(resolved.streams)!!
        println("stream → itag ${stream.itag} ${stream.qualityLabel} ${stream.sampleRateLabel}, loudness ${resolved.loudnessDb}")
        val range = Request.Builder().url(stream.url).header("Range", "bytes=0-65535")
            .header("User-Agent", InnerTubeClient.STREAM_USER_AGENT).build()
        http.newCall(range).execute().use { assertEquals(206, it.code); assertEquals(65536, it.body!!.bytes().size) }

        // Regression for "Gagal memutar: Response code: 403": a music track may be bot-gated
        // for ANDROID_VR on this network, and IOS/ANDROID then answer OK with URLs that 403.
        // The extractor must either return a URL that really downloads or fail with a clear message.
        try {
            val gated = yt.player(song.videoId)
            val s = AudioStreamSelector.selectForPlayback(gated.streams)!!
            val len = s.contentLength!!
            // The tail, not the head: PO-token-less URLs serve the first MB and 403 the rest.
            val req = Request.Builder().url(s.url).header("Range", "bytes=${len - 65536}-${len - 1}").header("User-Agent", gated.userAgent!!).build()
            http.newCall(req).execute().use { assertEquals("client ${gated.clientName}", 206, it.code) }
            println("gated song → playable via ${gated.clientName}")
        } catch (e: com.nadatube.app.extractor.ExtractionException) {
            println("gated song → ${e.message}")
            assertTrue(e.message!!.contains("memblokir"))
        }

        val radio = yt.radio(song.videoId)
        println("radio → ${radio.size} tracks")
        assertTrue(radio.size > 5)

        val sb = SponsorBlockClient(http).segments("dQw4w9WgXcQ")
        println("sponsorblock → ${sb.size} segments")

        val lyrics = LyricsClient(http).lyrics(song)
        println("lyrics → synced=${lyrics?.isSynced} lines=${lyrics?.lines?.size}")
    }
}
