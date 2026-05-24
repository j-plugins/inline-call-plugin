package com.github.xepozz.inline_call.base.api

/**
 * Piecewise-linear [OffsetMapping] backed by a sorted list of preserved
 * segments. A *segment* is a contiguous run of characters kept verbatim
 * in both the normalized and the original text; the gaps between
 * segments in the original text are stripped decoration
 * (PHPDoc opener, leading `* `, XML `<!--`, YAML `# `, and so on).
 *
 * Invariants checked at construction time:
 * - segments are sorted by [Segment.normalizedStart];
 * - segments do not overlap in either coordinate system;
 * - `normalizedStart + length <= normalizedLength`;
 * - `originalStart + length <= originalLength`;
 * - all `length` values are non-negative.
 *
 * Lookups are `O(log n)` via binary search; for the common case of a
 * single preserved segment (XML, line comments) lookup is `O(1)` after
 * the first comparison.
 */
class SegmentedOffsetMapping(
    private val segments: List<Segment>,
    private val normalizedLength: Int,
    private val originalLength: Int,
) : OffsetMapping {

    data class Segment(val normalizedStart: Int, val originalStart: Int, val length: Int)

    init {
        require(normalizedLength >= 0) { "normalizedLength must be >= 0, got $normalizedLength" }
        require(originalLength >= 0) { "originalLength must be >= 0, got $originalLength" }
        var prevNormEnd = 0
        var prevOrigEnd = 0
        for ((idx, s) in segments.withIndex()) {
            require(s.length >= 0) { "segment[$idx] length must be >= 0, got ${s.length}" }
            require(s.normalizedStart >= prevNormEnd) {
                "segment[$idx] normalizedStart ${s.normalizedStart} overlaps previous end $prevNormEnd"
            }
            require(s.originalStart >= prevOrigEnd) {
                "segment[$idx] originalStart ${s.originalStart} overlaps previous end $prevOrigEnd"
            }
            require(s.normalizedStart + s.length <= normalizedLength) {
                "segment[$idx] exceeds normalizedLength ($normalizedLength)"
            }
            require(s.originalStart + s.length <= originalLength) {
                "segment[$idx] exceeds originalLength ($originalLength)"
            }
            prevNormEnd = s.normalizedStart + s.length
            prevOrigEnd = s.originalStart + s.length
        }
    }

    /**
     * Maps a normalized offset to the corresponding offset in the
     * original text.
     *
     * - For an offset inside a segment, returns the exact original
     *   position.
     * - For `normalizedOffset == normalizedLength` (the end sentinel),
     *   returns the document offset immediately *after* the last
     *   preserved character — i.e. `lastSegment.originalStart +
     *   lastSegment.length`, or [originalLength] when the mapping is
     *   empty. This makes
     *   `originalRange = toOriginal(start)..toOriginal(start+len)`
     *   correctly span the matched substring even when trailing
     *   decoration (e.g. a newline followed by the PHPDoc closer)
     *   follows it in the original text.
     * - For an offset that falls between two segments (which can only
     *   happen when segments have zero-length gaps in normalized
     *   coordinates — they do not, by construction), it returns the
     *   originalStart of the next segment.
     */
    override fun toOriginal(normalizedOffset: Int): Int {
        require(normalizedOffset in 0..normalizedLength) {
            "normalizedOffset $normalizedOffset out of bounds [0, $normalizedLength]"
        }
        if (normalizedOffset == normalizedLength) {
            return segments.lastOrNull()?.let { it.originalStart + it.length } ?: originalLength
        }
        val idx = findSegmentByNormalized(normalizedOffset)
        if (idx >= 0) {
            val s = segments[idx]
            return s.originalStart + (normalizedOffset - s.normalizedStart)
        }
        // Should not happen: every normalized offset within [0, normalizedLength)
        // belongs to exactly one segment (we never strip from normalized text).
        // Defensive fallback: clamp to nearest segment start.
        val ins = -(idx + 1)
        return if (ins < segments.size) segments[ins].originalStart else originalLength
    }

    /**
     * Maps an original offset back to a normalized offset.
     *
     * - For an offset inside a segment, returns the exact normalized
     *   position.
     * - For an offset that lands on stripped decoration, **clamps right**
     *   to the next segment's `normalizedStart`. The contract intentionally
     *   does not preserve round-trip equality on stripped chars.
     * - For `originalOffset == originalLength`, returns [normalizedLength].
     */
    override fun toNormalized(originalOffset: Int): Int {
        require(originalOffset in 0..originalLength) {
            "originalOffset $originalOffset out of bounds [0, $originalLength]"
        }
        if (originalOffset == originalLength) return normalizedLength
        val idx = findSegmentByOriginal(originalOffset)
        if (idx >= 0) {
            val s = segments[idx]
            return s.normalizedStart + (originalOffset - s.originalStart)
        }
        // Clamp right to the next segment in original coordinates.
        val ins = -(idx + 1)
        return if (ins < segments.size) segments[ins].normalizedStart else normalizedLength
    }

    private fun findSegmentByNormalized(offset: Int): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                offset < s.normalizedStart -> hi = mid - 1
                offset >= s.normalizedStart + s.length -> lo = mid + 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    private fun findSegmentByOriginal(offset: Int): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                offset < s.originalStart -> hi = mid - 1
                offset >= s.originalStart + s.length -> lo = mid + 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    companion object {
        /**
         * Builds a degenerate one-segment mapping where normalized and
         * original coordinates coincide. Equivalent to
         * [OffsetMapping.Identity] but materialized as a [SegmentedOffsetMapping]
         * so callers can stay on the new API.
         */
        fun identity(length: Int): SegmentedOffsetMapping {
            require(length >= 0) { "length must be >= 0, got $length" }
            val segments = if (length == 0) emptyList() else listOf(Segment(0, 0, length))
            return SegmentedOffsetMapping(segments, length, length)
        }
    }
}
