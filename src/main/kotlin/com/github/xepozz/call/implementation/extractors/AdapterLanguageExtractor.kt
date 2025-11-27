package com.github.xepozz.call.implementation.extractors

import com.github.xepozz.call.elements.DefaultCommentElementsProvider
import com.github.xepozz.call.elements.ExecutionElementsProvider
import com.github.xepozz.call.implementation.api.ExtractedBlock
import com.github.xepozz.call.implementation.api.LanguageTextExtractor
import com.github.xepozz.call.implementation.api.OffsetMapping
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Adapter extractor that leverages the existing ExecutionElementsProvider EP
 * to build ExtractedBlock instances from suitable PSI elements (typically comments).
 */
class AdapterLanguageExtractor : LanguageTextExtractor {

    override fun isApplicable(file: PsiFile): Boolean = true

    override fun extract(file: PsiFile): List<ExtractedBlock> {
        val provider = chooseElementsProvider(file)
        val result = mutableListOf<ExtractedBlock>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (provider.isSuitable(element)) {
                    val text = element.text
                    result.add(
                        ExtractedBlock(
                            element = element,
                            originalRange = element.textRange ?: TextRange(0, text.length),
                            originalText = text,
                            normalizedText = text,
                            mapping = OffsetMapping.Identity
                        )
                    )
                }
                super.visitElement(element)
            }
        })
        return result
    }

    private fun chooseElementsProvider(file: PsiFile): ExecutionElementsProvider {
        val list = EP_EXECUTION_ELEMENTS.extensionList
        return list.firstOrNull { it.isApplicable(file) } ?: DefaultCommentElementsProvider
    }

    companion object {
        private val EP_EXECUTION_ELEMENTS: ExtensionPointName<ExecutionElementsProvider> =
            ExecutionElementsProvider.EP_NAME
    }
}
