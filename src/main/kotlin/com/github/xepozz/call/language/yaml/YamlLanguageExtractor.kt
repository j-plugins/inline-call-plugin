package com.github.xepozz.call.language.yaml

import com.github.xepozz.call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile

class YamlLanguageExtractor : BaseLanguageTextExtractor() {
    override fun isApplicable(file: PsiFile): Boolean = file is YAMLFile
}
