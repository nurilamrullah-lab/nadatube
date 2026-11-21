package com.nadatube.app.lyrics

import com.nadatube.app.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses timestamps of different precision and sorts`() {
        val lines = LrcParser.parse(
            """
            [ar:Coldplay]
            [00:12.34]Look at the stars
            [00:05]Intro
            [01:02.5]Chorus
            [00:20.123]Look how they shine
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LyricLine(5_000, "Intro"),
                LyricLine(12_340, "Look at the stars"),
                LyricLine(20_123, "Look how they shine"),
                LyricLine(62_500, "Chorus"),
            ),
            lines,
        )
    }

    @Test
    fun `multiple tags per line and offset`() {
        val lines = LrcParser.parse("[offset:500]\n[00:10.00][00:30.00]Yeah")
        assertEquals(listOf(9_500L, 29_500L), lines.map { it.timeMs })
        assertEquals(setOf("Yeah"), lines.map { it.text }.toSet())
    }

    @Test
    fun `active line lookup`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, LrcParser.activeIndex(lines, 500))
        assertEquals(0, LrcParser.activeIndex(lines, 1_000))
        assertEquals(1, LrcParser.activeIndex(lines, 8_999))
        assertEquals(2, LrcParser.activeIndex(lines, 100_000))
        assertEquals(-1, LrcParser.activeIndex(emptyList(), 100))
    }

    @Test
    fun `title cleaner strips youtube noise`() {
        assertEquals("Yellow", TitleCleaner.clean("Coldplay - Yellow (Official Video)", "Coldplay"))
        assertEquals("Lagu Rindu", TitleCleaner.clean("Lagu Rindu [Video Lirik]"))
        assertEquals("Fix You", TitleCleaner.clean("Fix You (Official Audio) (HD)"))
        assertEquals("Song (Live)", TitleCleaner.clean("Song (Live)"))
    }
}
