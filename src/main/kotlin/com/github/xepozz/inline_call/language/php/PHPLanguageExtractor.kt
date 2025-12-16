package com.github.xepozz.inline_call.language.php

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.PhpFile

class PHPLanguageExtractor : BaseLanguageTextExtractor() {
    override fun isApplicable(file: PsiFile): Boolean = file is PhpFile
}
