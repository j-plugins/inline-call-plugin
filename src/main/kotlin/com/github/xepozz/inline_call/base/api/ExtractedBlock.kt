package com.github.xepozz.inline_call.base.api

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * A block of text extracted from PSI that can be processed by features.
 * `text` is the normalized comment body; `mapping` ties every offset in
 * `text` back to a document offset relative to `originalRange.startOffset`.
 *
 * The default mapping is a degenerate identity sized to the supplied
 * text — convenient for synthetic blocks in tests; production
 * extractors always pass a real [SegmentedOffsetMapping].
 */
data class ExtractedBlock(
    val element: PsiElement,
    val originalRange: TextRange,
    val text: String,
    val mapping: OffsetMapping = SegmentedOffsetMapping.identity(text.length),
)
