package com.github.xepozz.inline_call.base.normalizers

import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.api.NormalizedComment
import com.github.xepozz.inline_call.base.api.SegmentedOffsetMapping

/**
 * Strips the leading marker of a single-line comment (`//`, `#`, ...)
 * together with at most one space after it. The marker argument is
 * interpreted as a regex fragment and wrapped in a non-capturing group
 * so callers can pass alternatives like `"//|#"` without worrying about
 * precedence.
 *
 * The normalized text is a single contiguous segment; multi-line input
 * is not split (single-line comments are by definition one line in the
 * source).
 */
class SingleLineCommentNormalizer(markerRegex: String) : CommentNormalizer {
    private val leading = Regex("""^\s*(?:$markerRegex)\s?""")

    override fun normalize(rawText: String): NormalizedComment {
        val match = leading.find(rawText)
        val stripped = match?.range?.last?.plus(1) ?: 0
        val text = rawText.substring(stripped)
        val segments = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(SegmentedOffsetMapping.Segment(0, stripped, text.length))
        }
        return NormalizedComment(
            text = text,
            mapping = SegmentedOffsetMapping(segments, text.length, rawText.length),
        )
    }
}
