package com.github.xepozz.call.language.xml

import com.github.xepozz.call.base.api.ExtractedBlock
import com.github.xepozz.call.base.api.LanguageTextExtractor
import com.github.xepozz.call.base.api.OffsetMapping
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlFile
import com.intellij.util.text.findTextRange

/**
 * Default extractor that reads plain PsiComment elements and returns them as blocks.
 * This serves as a language-agnostic fallback when no language-specific extractors exist.
 */
class XmlLanguageExtractor : LanguageTextExtractor {

    override fun isApplicable(file: PsiFile): Boolean = file is XmlFile

    override fun extract(file: PsiFile): List<ExtractedBlock> {
        val result = mutableListOf<ExtractedBlock>()
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitComment(element: PsiComment) {
                if (element is XmlComment) {
                    val text = element.commentText
                    val textRange = element.text.findTextRange(text)?.shiftRight(element.textOffset)
                    if (textRange != null) {
                        result.add(
                            ExtractedBlock(
                                element = element,
                                originalRange = textRange,
                                text = text,
                                mapping = OffsetMapping.Identity
                            )
                        )
                    }
                }
                super.visitElement(element)
            }
        })
        return result
    }
}
