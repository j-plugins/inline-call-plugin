package com.github.xepozz.inline_call.language.xml

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.github.xepozz.inline_call.base.normalizers.XmlCommentNormalizer
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile

class XmlLanguageExtractor : BaseLanguageTextExtractor(XmlCommentNormalizer) {
    override fun isApplicable(file: PsiFile): Boolean = file is XmlFile
}
