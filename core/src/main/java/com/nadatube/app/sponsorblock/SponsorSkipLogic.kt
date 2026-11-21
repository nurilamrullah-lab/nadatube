package com.nadatube.app.sponsorblock

import com.nadatube.app.model.SponsorSegment

/** Pure auto-skip rules, kept separate from the player so they can be unit-tested. */
object SponsorSkipLogic {
    /** Segments shorter than this are not worth an audible jump. */
    const val MIN_SEGMENT_MS = 1_000L

    /** Skip only when we are at least this far before the segment's end. */
    const val END_TOLERANCE_MS = 400L

    val DEFAULT_CATEGORIES = setOf("sponsor", "intro", "outro", "selfpromo", "interaction", "music_offtopic")

    val CATEGORY_LABELS = mapOf(
        "sponsor" to "Sponsor",
        "intro" to "Intro",
        "outro" to "Outro",
        "selfpromo" to "Promosi diri",
        "interaction" to "Ajakan subscribe",
        "music_offtopic" to "Bagian non-musik",
        "preview" to "Cuplikan",
        "filler" to "Pengisi",
    )

    /** Merges overlapping/adjacent skip segments so one seek clears the whole block. */
    fun merge(segments: List<SponsorSegment>, gapMs: Long = 250L): List<SponsorSegment> {
        val sorted = segments.filter { it.actionType == "skip" && it.lengthMs >= MIN_SEGMENT_MS }.sortedBy { it.startMs }
        val out = mutableListOf<SponsorSegment>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs + gapMs) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            } else {
                out += s
            }
        }
        return out
    }

    /**
     * Returns the segment to skip at [positionMs], or null.
     * A segment the user already skipped (and then seeked back into) is not skipped again.
     */
    fun segmentToSkip(
        positionMs: Long,
        segments: List<SponsorSegment>,
        alreadySkipped: Set<String>,
        enabledCategories: Set<String> = DEFAULT_CATEGORIES,
    ): SponsorSegment? = merge(segments.filter { it.category in enabledCategories }).firstOrNull {
        it.uuid !in alreadySkipped && positionMs >= it.startMs && positionMs < it.endMs - END_TOLERANCE_MS
    }

    /** Where to seek for [segment]; null means "segment runs to the end, go to next track". */
    fun seekTarget(segment: SponsorSegment, durationMs: Long): Long? =
        if (durationMs > 0 && segment.endMs >= durationMs - END_TOLERANCE_MS) null else segment.endMs

    /** Total time saved by skipping, for the UI. */
    fun totalSkippableMs(segments: List<SponsorSegment>, enabledCategories: Set<String> = DEFAULT_CATEGORIES): Long =
        merge(segments.filter { it.category in enabledCategories }).sumOf { it.lengthMs }
}
