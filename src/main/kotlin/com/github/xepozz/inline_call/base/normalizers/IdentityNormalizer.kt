package com.github.xepozz.inline_call.base.normalizers

import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.api.NormalizedComment
import com.github.xepozz.inline_call.base.api.SegmentedOffsetMapping

/**
 * Pass-through normalizer used by the fallback [AdapterLanguageExtractor]
 * and by tests that want to bypass decoration stripping. The normalized
 * text equals the raw text and the mapping is a single full-length
 * identity segment.
 */
object IdentityNormalizer : CommentNormalizer {
    override fun normalize(rawText: String): NormalizedComment =
        NormalizedComment(rawText, SegmentedOffsetMapping.identity(rawText.length))
}
