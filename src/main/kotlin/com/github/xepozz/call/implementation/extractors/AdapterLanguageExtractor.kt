package com.github.xepozz.call.implementation.extractors

import com.github.xepozz.call.implementation.api.ExtractedBlock
import com.github.xepozz.call.implementation.api.LanguageTextExtractor
import com.github.xepozz.call.implementation.api.OffsetMapping
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class AdapterLanguageExtractor : LanguageTextExtractor {
    override fun isApplicable(file: PsiFile): Boolean = true

    override fun extract(file: PsiFile): List<ExtractedBlock> {
        val result = mutableListOf<ExtractedBlock>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitComment(element: PsiComment) {
                val text = element.text
                result.add(
                    ExtractedBlock(
                        element = element,
                        originalRange = element.textRange ?: TextRange(0, text.length),
                        originalText = text,
                        normalizedText = text,
                        mapping = OffsetMapping.Identity
                    )
                )
                super.visitElement(element)
            }
        })
        return result
    }
}
