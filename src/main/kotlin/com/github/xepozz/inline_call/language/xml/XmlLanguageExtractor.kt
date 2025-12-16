package com.github.xepozz.inline_call.language.xml

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile

class XmlLanguageExtractor : BaseLanguageTextExtractor() {
    override fun isApplicable(file: PsiFile): Boolean = file is XmlFile
}
