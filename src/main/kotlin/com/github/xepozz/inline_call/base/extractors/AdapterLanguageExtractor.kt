package com.github.xepozz.inline_call.base.extractors

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile

class AdapterLanguageExtractor : BaseLanguageTextExtractor() {
    override fun isApplicable(file: PsiFile): Boolean = true
}
