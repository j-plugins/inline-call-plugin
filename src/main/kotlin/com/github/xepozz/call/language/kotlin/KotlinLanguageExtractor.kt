package com.github.xepozz.call.language.kotlin

import com.github.xepozz.call.implementation.api.ExtractedBlock
import com.github.xepozz.call.implementation.api.LanguageTextExtractor
import com.github.xepozz.call.implementation.api.OffsetMapping
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor

/**
 * Default extractor that reads plain PsiComment elements and returns them as blocks.
 * This serves as a language-agnostic fallback when no language-specific extractors exist.
 */
class KotlinLanguageExtractor : LanguageTextExtractor {

//    override fun isApplicable(file: PsiFile): Boolean = file is KtFile
    override fun isApplicable(file: PsiFile): Boolean = false

    override fun extract(file: PsiFile): List<ExtractedBlock> {
        val result = mutableListOf<ExtractedBlock>()
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiComment) {
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
                }
                super.visitElement(element)
            }
        })
        return result
    }
}
