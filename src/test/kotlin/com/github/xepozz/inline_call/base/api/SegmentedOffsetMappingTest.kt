package com.github.xepozz.inline_call.base.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-Kotlin tests for SegmentedOffsetMapping — no IntelliJ platform
 * harness needed.
 */
class SegmentedOffsetMappingTest {

    @Test
    fun `identity round-trips every offset`() {
        val mapping = SegmentedOffsetMapping.identity(10)
        for (i in 0..10) {
            assertEquals(i, mapping.toOriginal(i))
            assertEquals(i, mapping.toNormalized(i))
        }
    }

    @Test
    fun `identity of length zero is a valid empty mapping`() {
        val mapping = SegmentedOffsetMapping.identity(0)
        assertEquals(0, mapping.toOriginal(0))
        assertEquals(0, mapping.toNormalized(0))
    }

    @Test
    fun `single-segment with leading gap maps to original start of segment`() {
        // Original: "<!--foo-->", normalized: "foo".
        val mapping = SegmentedOffsetMapping(
            segments = listOf(SegmentedOffsetMapping.Segment(0, 4, 3)),
            normalizedLength = 3,
            originalLength = 10,
        )
        assertEquals(4, mapping.toOriginal(0))
        assertEquals(5, mapping.toOriginal(1))
    }

    @Test
    fun `end sentinel returns position right after last preserved char`() {
        // Original: "<!--foo-->", normalized: "foo". After 'foo' the
        // original position is 7 (the start of "-->"), not the document
        // end 10 — so a match spanning the whole body lands at [4, 7).
        val mapping = SegmentedOffsetMapping(
            segments = listOf(SegmentedOffsetMapping.Segment(0, 4, 3)),
            normalizedLength = 3,
            originalLength = 10,
        )
        assertEquals(7, mapping.toOriginal(3))
    }

    @Test
    fun `end sentinel falls back to originalLength when no segments`() {
        val mapping = SegmentedOffsetMapping(emptyList(), 0, 5)
        assertEquals(5, mapping.toOriginal(0))
    }

    @Test
    fun `identity end sentinel still returns length`() {
        // For identity mappings lastSegment.originalStart + length == length.
        val mapping = SegmentedOffsetMapping.identity(7)
        assertEquals(7, mapping.toOriginal(7))
    }

    @Test
    fun `multi-segment maps across PHPDoc-style gaps`() {
        // Original: "/**\n * Line one\n * shell: echo 42\n */"
        // Normalized: "Line one\nshell: echo 42"
        val segments = listOf(
            SegmentedOffsetMapping.Segment(0, 7, 8),    // "Line one"
            SegmentedOffsetMapping.Segment(8, 15, 1),   // "\n"
            SegmentedOffsetMapping.Segment(9, 19, 14),  // "shell: echo 42"
        )
        val mapping = SegmentedOffsetMapping(segments, normalizedLength = 23, originalLength = 38)

        assertEquals(7, mapping.toOriginal(0))     // 'L'
        assertEquals(14, mapping.toOriginal(7))    // 'e' in "one"
        assertEquals(15, mapping.toOriginal(8))    // '\n'
        assertEquals(19, mapping.toOriginal(9))    // 's' in "shell"
        assertEquals(32, mapping.toOriginal(22))   // last char '2'
        // End sentinel returns position right after last preserved char:
        // last segment (9, 19, 14) -> 19 + 14 = 33. The original text
        // continues with "\n */" past that point.
        assertEquals(33, mapping.toOriginal(23))
    }

    @Test
    fun `toNormalized clamps right on stripped char`() {
        // Original: "<!--foo-->", normalized: "foo" (gap at [0,4) and [7,10)).
        val mapping = SegmentedOffsetMapping(
            segments = listOf(SegmentedOffsetMapping.Segment(0, 4, 3)),
            normalizedLength = 3,
            originalLength = 10,
        )
        // Inside gap [0,4): clamp to next segment start (normalized 0).
        assertEquals(0, mapping.toNormalized(0))
        assertEquals(0, mapping.toNormalized(2))
        // Inside segment.
        assertEquals(0, mapping.toNormalized(4))
        assertEquals(1, mapping.toNormalized(5))
        assertEquals(2, mapping.toNormalized(6))
        // Trailing gap [7,10): no next segment, clamp to normalizedLength.
        assertEquals(3, mapping.toNormalized(7))
        assertEquals(3, mapping.toNormalized(9))
        assertEquals(3, mapping.toNormalized(10))
    }

    @Test
    fun `out-of-bounds normalized offset throws`() {
        val mapping = SegmentedOffsetMapping.identity(5)
        assertThrows(IllegalArgumentException::class.java) { mapping.toOriginal(-1) }
        assertThrows(IllegalArgumentException::class.java) { mapping.toOriginal(6) }
    }

    @Test
    fun `out-of-bounds original offset throws`() {
        val mapping = SegmentedOffsetMapping.identity(5)
        assertThrows(IllegalArgumentException::class.java) { mapping.toNormalized(-1) }
        assertThrows(IllegalArgumentException::class.java) { mapping.toNormalized(6) }
    }

    @Test
    fun `overlapping segments rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedOffsetMapping(
                segments = listOf(
                    SegmentedOffsetMapping.Segment(0, 0, 5),
                    SegmentedOffsetMapping.Segment(3, 10, 5),
                ),
                normalizedLength = 10,
                originalLength = 20,
            )
        }
    }

    @Test
    fun `segment exceeding originalLength rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedOffsetMapping(
                segments = listOf(SegmentedOffsetMapping.Segment(0, 5, 10)),
                normalizedLength = 10,
                originalLength = 10,
            )
        }
    }

    @Test
    fun `preserved-index property holds across many segments`() {
        // Build a 100-segment ladder: each segment 5 chars long, 2-char gap between.
        val segments = mutableListOf<SegmentedOffsetMapping.Segment>()
        var norm = 0
        var orig = 0
        repeat(100) {
            segments += SegmentedOffsetMapping.Segment(norm, orig, 5)
            norm += 5
            orig += 5 + 2
        }
        // The final 2-char gap after the last segment is *not* preserved
        // content. originalLength can include it (e.g. trailing `*/`)
        // without changing the per-index mapping.
        val mapping = SegmentedOffsetMapping(segments, norm, orig)
        // Every preserved index should map correctly and round-trip.
        for (i in 0 until norm) {
            val o = mapping.toOriginal(i)
            assertEquals(i, mapping.toNormalized(o))
        }
        // End sentinel: position right after last preserved char.
        val last = segments.last()
        assertEquals(last.originalStart + last.length, mapping.toOriginal(norm))
    }
}
