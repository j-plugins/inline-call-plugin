package com.github.xepozz.inline_call.base.normalizers

import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.api.NormalizedComment
import com.github.xepozz.inline_call.base.api.SegmentedOffsetMapping

/**
 * Normalizes C-style block comments — `/` `*` ... `*` `/` — and optionally
 * PHPDoc — `/` `*` `*` ... `*` `/` — by stripping:
 *
 * 1. the opening marker and any leading whitespace up to and including
 *    the first line terminator (so the first content line starts at
 *    normalized offset 0);
 * 2. the trailing whitespace plus closing `*` `/`;
 * 3. on every interior line, when [stripStarPrefix] is true, the leading
 *    `^\s*\*\s?` decoration — at most one space after the `*`, so deep
 *    indentation inside `@example` blocks is preserved.
 *
 * `\r\n` and `\n` are both treated as a single logical line terminator
 * for *stripping* purposes; both bytes still contribute to the original
 * offset so document positions stay accurate.
 *
 * Preserved line terminators between content lines are emitted as
 * single-character (`\n`) segments — the matcher patterns currently
 * never span them, but the mapping is built to handle multi-line
 * matches correctly anyway.
 */
class CStyleBlockNormalizer(private val stripStarPrefix: Boolean) : CommentNormalizer {

    override fun normalize(rawText: String): NormalizedComment {
        val opener = if (stripStarPrefix && rawText.startsWith("/**")) "/**" else "/*"
        if (!rawText.startsWith(opener) || !rawText.endsWith("*/")) {
            return IdentityNormalizer.normalize(rawText)
        }

        val builder = StringBuilder()
        val segments = mutableListOf<SegmentedOffsetMapping.Segment>()
        val rawLen = rawText.length
        val bodyEnd = rawLen - "*/".length

        // Phase 1: skip opener + optional whitespace + first newline.
        var orig = opener.length
        var sawNewline = false
        while (orig < bodyEnd) {
            val c = rawText[orig]
            if (c == '\n') {
                orig++
                sawNewline = true
                break
            }
            if (c == '\r') {
                orig++
                if (orig < bodyEnd && rawText[orig] == '\n') orig++
                sawNewline = true
                break
            }
            if (!c.isWhitespace()) break
            orig++
        }

        if (!sawNewline) {
            // Single-line block: /** shell: echo 1 */ or /* foo */
            // Strip a single leading space if present, the trailing
            // whitespace before */, and emit one segment.
            var start = orig
            var end = bodyEnd
            while (end > start && rawText[end - 1].isWhitespace()) end--
            val length = end - start
            if (length > 0) {
                builder.append(rawText, start, end)
                segments += SegmentedOffsetMapping.Segment(0, start, length)
            }
            return finish(builder, segments, rawLen)
        }

        // Phase 2: line-by-line scan over [orig, bodyEnd).
        while (orig < bodyEnd) {
            val lineStart = orig
            var lineEnd = orig
            while (lineEnd < bodyEnd && rawText[lineEnd] != '\n' && rawText[lineEnd] != '\r') {
                lineEnd++
            }

            // Decoration probe: where does non-decoration content start?
            // Decoration is leading whitespace + optional `* ` regardless
            // of [stripStarPrefix]; the flag only governs whether we
            // *emit* that decoration when content exists on the line.
            var decoEnd = lineStart
            while (decoEnd < lineEnd && rawText[decoEnd].isWhitespace()) decoEnd++
            if (decoEnd < lineEnd && rawText[decoEnd] == '*') {
                decoEnd++
                if (decoEnd < lineEnd && rawText[decoEnd] == ' ') decoEnd++
            }
            val isDecorationOnly = decoEnd >= lineEnd

            // Effective contentStart depends on stripping mode.
            val contentStart = if (stripStarPrefix) decoEnd else lineStart

            val contentLength = lineEnd - contentStart
            if (contentLength > 0 && !isDecorationOnly) {
                builder.append(rawText, contentStart, lineEnd)
                segments += SegmentedOffsetMapping.Segment(
                    normalizedStart = builder.length - contentLength,
                    originalStart = contentStart,
                    length = contentLength,
                )
            }

            // Handle line terminator: emit a single `\n` segment when we
            // are not at the last line. The original may be `\r\n`; we
            // map the segment onto the `\n` byte so toNormalized of `\r`
            // clamps right.
            orig = lineEnd
            if (orig < bodyEnd && rawText[orig] == '\r') orig++
            val newlineOrigin = orig
            if (orig < bodyEnd && rawText[orig] == '\n') {
                orig++
                // Look ahead: skip purely-decoration trailing lines before
                // the closer (e.g. ` */`). If there is no more content
                // before `*/`, suppress the trailing newline.
                val hasMoreContent = hasMoreContentBefore(rawText, orig, bodyEnd, stripStarPrefix)
                if (hasMoreContent) {
                    val normStart = builder.length
                    builder.append('\n')
                    segments += SegmentedOffsetMapping.Segment(
                        normalizedStart = normStart,
                        originalStart = newlineOrigin,
                        length = 1,
                    )
                }
            }
        }

        return finish(builder, segments, rawLen)
    }

    private fun finish(
        builder: StringBuilder,
        segments: List<SegmentedOffsetMapping.Segment>,
        originalLength: Int,
    ): NormalizedComment {
        val text = builder.toString()
        return NormalizedComment(
            text = text,
            mapping = SegmentedOffsetMapping(segments, text.length, originalLength),
        )
    }

    /**
     * After consuming a `\n` at offset `cursor - 1`, scans forward to
     * see whether at least one non-decoration character precedes
     * `bodyEnd`. Decoration is always leading whitespace + optional `*` +
     * optional single space — regardless of [stripStarPrefix]. The flag
     * only controls whether we *emit* the decoration on real content
     * lines; when it comes to deciding whether to emit the trailing
     * newline, a decoration-only line is decoration in both modes.
     */
    private fun hasMoreContentBefore(
        rawText: String,
        cursor: Int,
        bodyEnd: Int,
        @Suppress("UNUSED_PARAMETER") stripStar: Boolean,
    ): Boolean {
        var i = cursor
        while (i < bodyEnd) {
            val lineStart = i
            var lineEnd = i
            while (lineEnd < bodyEnd && rawText[lineEnd] != '\n' && rawText[lineEnd] != '\r') {
                lineEnd++
            }
            var contentStart = lineStart
            while (contentStart < lineEnd && rawText[contentStart].isWhitespace()) contentStart++
            if (contentStart < lineEnd && rawText[contentStart] == '*') {
                contentStart++
                if (contentStart < lineEnd && rawText[contentStart] == ' ') contentStart++
            }
            if (contentStart < lineEnd) return true
            i = lineEnd
            if (i < bodyEnd && rawText[i] == '\r') i++
            if (i < bodyEnd && rawText[i] == '\n') i++
        }
        return false
    }
}
