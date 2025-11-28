package com.github.xepozz.call.base.api

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.text.findTextRange

abstract class BaseLanguageTextExtractor : LanguageTextExtractor {
    override fun extract(file: PsiFile): List<ExtractedBlock> = PsiTreeUtil
        .findChildrenOfAnyType(file, PsiComment::class.java)
        .mapNotNull { element ->
            val text = element.text
            val textRange = element.text.findTextRange(text)?.shiftRight(element.textOffset)

            if (textRange == null) {
                null
            } else {
                ExtractedBlock(
                    element = element,
                    originalRange = textRange,
                    text = text,
                    mapping = OffsetMapping.Identity
                )
            }
        }
}