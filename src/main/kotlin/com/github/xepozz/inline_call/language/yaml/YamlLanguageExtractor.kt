package com.github.xepozz.inline_call.language.yaml

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.github.xepozz.inline_call.base.normalizers.SingleLineCommentNormalizer
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile

class YamlLanguageExtractor : BaseLanguageTextExtractor(SingleLineCommentNormalizer("#")) {
    override fun isApplicable(file: PsiFile): Boolean = file is YAMLFile
}
