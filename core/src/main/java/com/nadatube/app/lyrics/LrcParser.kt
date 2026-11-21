package com.nadatube.app.lyrics

import com.nadatube.app.model.LyricLine

object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Parses LRC text; supports multiple time tags per line and the [offset:] tag. */
    fun parse(lrc: String): List<LyricLine> {
        val offset = OFFSET_TAG.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return lrc.lineSequence().flatMap { raw ->
            val tags = TIME_TAG.findAll(raw).toList()
            if (tags.isEmpty()) return@flatMap emptySequence()
            val text = raw.substring(tags.last().range.last + 1).trim()
            tags.asSequence().map { m ->
                val (min, sec, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                LyricLine((min.toLong() * 60 + sec.toLong()) * 1000 + fracMs - offset, text)
            }
        }.map { if (it.timeMs < 0) it.copy(timeMs = 0) else it }.sortedBy { it.timeMs }.toList()
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/** Strips YouTube noise from titles so lyric lookups match ("Song (Official Video) [HD]" → "Song"). */
object TitleCleaner {
    private val NOISE = Regex(
        """\s*[(\[](official|lyric|lyrics|audio|video|music video|mv|hd|hq|4k|visualizer|official audio|official video|official music video|official lyric video|video lirik|lirik)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(title: String, artist: String? = null): String {
        var t = NOISE.replace(title, "").trim()
        if (artist != null && artist.isNotBlank()) {
            val prefix = Regex("^" + Regex.escape(artist) + """\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)
            t = prefix.replace(t, "")
        }
        return t.trim().ifEmpty { title }
    }
}
