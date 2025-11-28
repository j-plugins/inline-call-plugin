package com.github.xepozz.call.language.kotlin

import com.github.xepozz.call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile

class KotlinLanguageExtractor : BaseLanguageTextExtractor() {
//    override fun isApplicable(file: PsiFile): Boolean = file is KtFile
    override fun isApplicable(file: PsiFile): Boolean = false
}
