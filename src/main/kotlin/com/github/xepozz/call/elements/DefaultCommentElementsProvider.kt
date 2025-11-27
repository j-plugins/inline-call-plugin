package com.github.xepozz.call.elements

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Default provider that treats any PsiComment as a suitable element.
 * Used as a fallback when there is no language‑specific provider.
 */
object DefaultCommentElementsProvider : ExecutionElementsProvider {
    override fun isApplicable(file: PsiFile): Boolean = true

    override fun isSuitable(element: PsiElement): Boolean = element is PsiComment
}
