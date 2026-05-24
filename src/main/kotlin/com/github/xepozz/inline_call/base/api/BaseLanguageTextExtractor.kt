package com.github.xepozz.inline_call.base.api

import com.github.xepozz.inline_call.base.normalizers.IdentityNormalizer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.text.findTextRange

/**
 * Walks every [PsiComment] in [file] and emits one [ExtractedBlock] per
 * element. The block's `text` and `mapping` are produced by the
 * [normalizer]; the `originalRange` always spans the whole raw comment
 * so adapters can map normalized offsets back into the document via
 * `block.originalRange.startOffset + block.mapping.toOriginal(...)`.
 *
 * Subclasses that need per-element dispatch (PHPDoc vs `//` vs `#`)
 * should override [normalizerFor] instead of [extract].
 */
abstract class BaseLanguageTextExtractor(
    private val defaultNormalizer: CommentNormalizer = IdentityNormalizer,
) : LanguageTextExtractor {

    /**
     * Picks the normalizer for the given PSI element. Defaults to the
     * constructor-supplied [defaultNormalizer]; override to dispatch on
     * element type / class.
     */
    protected open fun normalizerFor(element: PsiElement): CommentNormalizer = defaultNormalizer

    override fun extract(file: PsiFile): List<ExtractedBlock> = PsiTreeUtil
        .findChildrenOfAnyType(file, PsiComment::class.java)
        .mapNotNull { element ->
            val text = element.text
            val textRange = element.text.findTextRange(text)?.shiftRight(element.textOffset)
                ?: return@mapNotNull null
            val normalized = normalizerFor(element).normalize(text)
            ExtractedBlock(
                element = element,
                originalRange = textRange,
                text = normalized.text,
                mapping = normalized.mapping,
            )
        }
}
