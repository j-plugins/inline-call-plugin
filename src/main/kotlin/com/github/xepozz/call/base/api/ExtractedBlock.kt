package com.github.xepozz.call.base.api

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * A block of text extracted from PSI that can be processed by features.
 */
data class ExtractedBlock(
    val element: PsiElement,
    val originalRange: TextRange,
    val text: String,
    val mapping: OffsetMapping = OffsetMapping.Identity,
)
