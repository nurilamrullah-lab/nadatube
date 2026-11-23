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

        val radio = yt.radio(song.videoId)
        println("radio → ${radio.size} tracks")
        assertTrue(radio.size > 5)

        val sb = SponsorBlockClient(http).segments("dQw4w9WgXcQ")
        println("sponsorblock → ${sb.size} segments")

        val lyrics = LyricsClient(http).lyrics(song)
        println("lyrics → synced=${lyrics?.isSynced} lines=${lyrics?.lines?.size}")
    }
}
