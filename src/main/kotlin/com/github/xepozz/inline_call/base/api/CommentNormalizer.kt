package com.github.xepozz.inline_call.base.api

/**
 * Strips per-language comment decoration (markers, leading stars,
 * indentation) and emits the surviving text alongside the offset
 * mapping that ties every normalized character back to its original
 * position inside the raw comment text.
 *
 * Implementations must satisfy, for every preserved index `i`:
 *
 *     normalized.text[i] == rawText[mapping.toOriginal(i)]
 *
 * and `mapping.toOriginal(normalized.text.length) == rawText.length`.
 *
 * Implementations are pure functions of [rawText] and must not call
 * into PSI; the per-element dispatch happens in the extractor.
 */
interface CommentNormalizer {
    fun normalize(rawText: String): NormalizedComment
}

/**
 * The result of [CommentNormalizer.normalize]: the normalized text plus
 * the mapping back to the original offsets inside the raw comment.
 */
data class NormalizedComment(
    val text: String,
    val mapping: SegmentedOffsetMapping,
)
