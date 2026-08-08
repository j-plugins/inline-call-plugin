package com.github.xepozz.inline_call.language.php

import com.github.xepozz.inline_call.base.api.BaseLanguageTextExtractor
import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.normalizers.CStyleBlockNormalizer
import com.github.xepozz.inline_call.base.normalizers.SingleLineCommentNormalizer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpFile

/**
 * Per-element comment dispatch for PHP:
 *
 * - `PhpDocComment` (`/** ... */`) -> [CStyleBlockNormalizer] with
 *   `stripStarPrefix = true`, so leading `* ` is dropped on every line.
 *   `PhpDocComment` extends `PsiDocCommentBase` which extends
 *   `PsiComment`, so the base extractor walks it without changes.
 * - `C_STYLE_COMMENT` token (`/* ... */`, no PHPDoc) ->
 *   [CStyleBlockNormalizer] with `stripStarPrefix = false`.
 * - line comments (`//`, `#`) -> [SingleLineCommentNormalizer].
 */
class PHPLanguageExtractor : BaseLanguageTextExtractor() {

    private val phpDocNormalizer = CStyleBlockNormalizer(stripStarPrefix = true)
    private val cStyleNormalizer = CStyleBlockNormalizer(stripStarPrefix = false)
    private val lineNormalizer = SingleLineCommentNormalizer("//|#")

    override fun isApplicable(file: PsiFile): Boolean = file is PhpFile

    override fun normalizerFor(element: PsiElement): CommentNormalizer {
        if (element is PhpDocComment) return phpDocNormalizer
        if (element is PsiComment) {
            val type = element.tokenType
            if (type == PhpTokenTypes.C_STYLE_COMMENT) return cStyleNormalizer
            if (type == PhpTokenTypes.LINE_COMMENT) return lineNormalizer
        }
        return super.normalizerFor(element)
    }
}
