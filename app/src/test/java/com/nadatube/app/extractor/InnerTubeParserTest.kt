package com.nadatube.app.extractor

import com.nadatube.app.model.AudioCodec
import com.nadatube.app.model.SearchFilter
import com.nadatube.app.model.SearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeParserTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()

    @Test
    fun `player response yields audio-only streams with metadata and loudness`() {
        val r = InnerTubeParser.parsePlayer(fixture("player_android_vr"), nowMs = 0)
        assertEquals("dQw4w9WgXcQ", r.track.videoId)
        assertTrue(r.track.title.isNotBlank())
        assertTrue(r.track.durationMs > 200_000)
        assertTrue(r.streams.isNotEmpty())
        assertTrue("only audio mime types", r.streams.all { it.mimeType.startsWith("audio/") })
        val opus = r.streams.first { it.itag == 251 }
        assertEquals(AudioCodec.OPUS, opus.codec)
        assertEquals("audio/webm", opus.mimeType)
        assertEquals(48_000, opus.sampleRate)
        assertEquals(160, opus.nominalKbps)
        val aac = r.streams.first { it.itag == 140 }
        assertEquals(AudioCodec.AAC, aac.codec)
        assertEquals("m4a", aac.extension)
        assertNotNull(r.loudnessDb)
        assertTrue(r.expiresAtMs > 0)
    }

    @Test
    fun `unplayable status throws with reason`() {
        val body = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Please sign in"}}"""
        val e = assertThrows(PlayabilityException::class.java) { InnerTubeParser.parsePlayer(body) }
        assertTrue(e.message!!.contains("Please sign in"))
        assertFalse("age/login gate is not the bot check", e.isBotCheck)
        val bot = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Login untuk mengonfirmasi bahwa Anda bukan bot"}}"""
        assertTrue(assertThrows(PlayabilityException::class.java) { InnerTubeParser.parsePlayer(bot) }.isBotCheck)
    }

    @Test
    fun `expiry comes from the url expire parameter when it is earlier`() {
        val now = 1_700_000_000_000L - 3_600_000L // one hour before the fixture's expire=1700000000
        val r = InnerTubeParser.parsePlayer(fixture("player_android_vr"), nowMs = now)
        assertEquals(1_700_000_000_000L, r.expiresAtMs)
        assertEquals(1_700_000_000_000L, InnerTubeParser.urlExpiryMs("https://x/videoplayback?a=1&expire=1700000000&b=2"))
        assertNull(InnerTubeParser.urlExpiryMs("https://x/videoplayback?a=1"))
    }

    @Test
    fun `ciphered and video formats are skipped`() {
        val body = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","title":"t","author":"a - Topic","lengthSeconds":"10"},
             "streamingData":{"adaptiveFormats":[
               {"itag":137,"mimeType":"video/mp4; codecs=\"avc1\"","url":"https://v"},
               {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","signatureCipher":"s=x&url=y"},
               {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://a","bitrate":130000}
             ]}}
        """.trimIndent()
        val r = InnerTubeParser.parsePlayer(body)
        assertEquals(listOf(140), r.streams.map { it.itag })
        assertEquals("a", r.track.artist) // " - Topic" suffix removed
    }

    @Test
    fun `no direct audio streams throws`() {
        val body = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk"},"streamingData":{"adaptiveFormats":[]}}"""
        assertThrows(ExtractionException::class.java) { InnerTubeParser.parsePlayer(body) }
    }

    @Test
    fun `song search parses title artist album duration and hi-res cover`() {
        val page = InnerTubeParser.parseSearch(fixture("search_songs"), SearchFilter.SONGS)
        assertTrue(page.items.isNotEmpty())
        val first = (page.items.first() as SearchItem.Song).track
        assertEquals("9qnqYL0eNNI", first.videoId)
        assertEquals("Yellow", first.title)
        assertEquals("Coldplay", first.artist)
        assertEquals("Parachutes", first.album)
        assertEquals(267_000L, first.durationMs)
        assertTrue(first.thumbnailUrl!!.contains("w544-h544"))
        assertNotNull(page.continuation)
    }

    @Test
    fun `album artist and playlist searches parse browse ids`() {
        val albums = InnerTubeParser.parseSearch(fixture("search_albums"), SearchFilter.ALBUMS).items
        val album = albums.first() as SearchItem.Album
        assertEquals("Parachutes", album.title)
        assertEquals("Coldplay", album.artist)
        assertEquals("2000", album.year)
        assertTrue(album.id.isNotBlank())

        val artist = InnerTubeParser.parseSearch(fixture("search_artists"), SearchFilter.ARTISTS).items.first() as SearchItem.Artist
        assertEquals("Coldplay", artist.name)
        assertFalse(artist.subtitle.startsWith("Artist"))

        val playlists = InnerTubeParser.parseSearch(fixture("search_playlists"), SearchFilter.PLAYLISTS).items
        assertTrue(playlists.all { it is SearchItem.Playlist && it.id.isNotBlank() })
    }

    @Test
    fun `album browse yields tracks with header cover fallback`() {
        val tracks = InnerTubeParser.parseTrackList(fixture("browse_album"))
        assertTrue(tracks.isNotEmpty())
        val first = tracks.first()
        assertEquals("yWeuUwpEQfs", first.videoId)
        assertEquals("Don't Panic", first.title)
        assertEquals(137_000L, first.durationMs)
        assertTrue(tracks.all { it.thumbnailUrl != null })
    }

    @Test
    fun `radio next response parses queue`() {
        val tracks = InnerTubeParser.parseRadio(fixture("next_radio"))
        assertTrue(tracks.size >= 2)
        assertTrue(tracks.all { it.videoId.length == 11 && it.title.isNotBlank() && it.durationMs > 0 })
        assertEquals(tracks.size, tracks.map { it.videoId }.toSet().size)
    }

    @Test
    fun `indonesian locale song row parses dotted duration`() {
        val body = fixture("search_songs").replace("\"4:27\"", "\"4.27\"")
        val first = (InnerTubeParser.parseSearch(body, SearchFilter.SONGS).items.first() as SearchItem.Song).track
        assertEquals(267_000L, first.durationMs)
        assertEquals("Coldplay", first.artist)
    }

    @Test
    fun `duration parsing`() {
        assertEquals(267_000L, InnerTubeParser.parseDuration("4:27"))
        assertEquals(3_723_000L, InnerTubeParser.parseDuration("1:02:03"))
        // hl=id formats durations with a dot
        assertEquals(267_000L, InnerTubeParser.parseDuration("4.27"))
        assertNull(InnerTubeParser.parseDuration("abc"))
    }

    @Test
    fun `thumbnail upscaling only touches google image cdn`() {
        assertEquals(
            "https://lh3.googleusercontent.com/x=w544-h544-l90-rj",
            InnerTubeParser.upscaleThumbnail("https://lh3.googleusercontent.com/x=w120-h120-l90-rj"),
        )
        assertEquals("https://i.ytimg.com/vi/a/hq.jpg", InnerTubeParser.upscaleThumbnail("https://i.ytimg.com/vi/a/hq.jpg"))
    }
}
