package com.github.xepozz.call.base.api

import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.psi.PsiFile

/**
 * Extractor of text blocks from a file or element (language-specific).
 */
interface LanguageTextExtractor {
    fun isApplicable(file: PsiFile): Boolean
    fun extract(file: PsiFile): List<ExtractedBlock>

    companion object {
        val EP_NAME: ProjectExtensionPointName<LanguageTextExtractor> =
            ProjectExtensionPointName("com.github.xepozz.call.languageTextExtractor")

        fun getApplicable(file: PsiFile): List<LanguageTextExtractor> =
            EP_NAME.getExtensions(file.project).filter { it.isApplicable(file) }
    }
}