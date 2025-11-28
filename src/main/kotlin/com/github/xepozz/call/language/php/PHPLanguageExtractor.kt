package com.github.xepozz.call.language.php

import com.github.xepozz.call.base.api.BaseLanguageTextExtractor
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.PhpFile

class PHPLanguageExtractor : BaseLanguageTextExtractor() {
    override fun isApplicable(file: PsiFile): Boolean = file is PhpFile
}
