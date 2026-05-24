package com.github.xepozz.inline_call.base.normalizers

import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.api.NormalizedComment
import com.github.xepozz.inline_call.base.api.SegmentedOffsetMapping

/**
 * Strips the `<!--` / `-->` delimiters of an XML comment, plus at most
 * one space immediately after the opener and before the closer. Interior
 * whitespace and indentation are preserved verbatim — the matcher
 * regexes already handle `\s*` themselves, and stripping inner indent
 * would lose offsets without buying anything.
 *
 * Multi-line XML comments have no per-line decoration, so the result is
 * always a single segment.
 */
object XmlCommentNormalizer : CommentNormalizer {
    private const val OPEN = "<!--"
    private const val CLOSE = "-->"

    override fun normalize(rawText: String): NormalizedComment {
        if (!rawText.startsWith(OPEN) || !rawText.endsWith(CLOSE) || rawText.length < OPEN.length + CLOSE.length) {
            // Defensive: not a well-formed XML comment text, behave like identity.
            return IdentityNormalizer.normalize(rawText)
        }
        var start = OPEN.length
        var end = rawText.length - CLOSE.length
        if (start < end && rawText[start] == ' ') start++
        if (start < end && rawText[end - 1] == ' ') end--
        val length = end - start
        val text = rawText.substring(start, end)
        val segments = if (length == 0) {
            emptyList()
        } else {
            listOf(SegmentedOffsetMapping.Segment(0, start, length))
        }
        return NormalizedComment(
            text = text,
            mapping = SegmentedOffsetMapping(segments, length, rawText.length),
        )
    }
}
