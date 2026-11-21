package com.nadatube.app.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {
    @Test
    fun `recognises common video link formats`() {
        val expected = YouTubeLink.Video("dQw4w9WgXcQ")
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc",
            "https://youtu.be/dQw4w9WgXcQ?si=xyz",
            "youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
            "  dQw4w9WgXcQ  ",
        ).forEach { assertEquals(it, expected, YouTubeUrlParser.parse(it)) }
    }

    @Test
    fun `playlist links`() {
        assertEquals(
            YouTubeLink.Playlist("PL1234567890"),
            YouTubeUrlParser.parse("https://www.youtube.com/playlist?list=PL1234567890"),
        )
        assertEquals(
            YouTubeLink.Playlist("OLAK5uy_abc", "dQw4w9WgXcQ"),
            YouTubeUrlParser.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc&v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `watch link inside a playlist plays the video`() {
        assertEquals(
            YouTubeLink.Video("dQw4w9WgXcQ"),
            YouTubeUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"),
        )
    }

    @Test
    fun `rejects non youtube input`() {
        assertNull(YouTubeUrlParser.parse("coldplay yellow"))
        assertNull(YouTubeUrlParser.parse("https://vimeo.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrlParser.parse("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeUrlParser.parse(""))
    }
}
