package com.github.xepozz.call.elements

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Extension point for discovering PSI elements that are suitable
 * for this plugin's features (such as inlay buttons for run/stop/etc.).
 *
 * Implementations are expected to be language-aware. If there is no
 * specific implementation for a language, the plugin will fallback to
 * a default provider that targets generic PsiComment elements.
 */
interface ExecutionElementsProvider {
    /**
     * Whether this provider is applicable for the given file (e.g., by language).
     */
    fun isApplicable(file: PsiFile): Boolean

    /**
     * Whether the given PSI element in an applicable file should be considered
     * by the plugin when scanning for runnable/comment markers.
     */
    fun isSuitable(element: PsiElement): Boolean

    companion object {
        val EP_NAME: ExtensionPointName<ExecutionElementsProvider> =
            ExtensionPointName.create("com.github.xepozz.call.executionElementsProvider")
    }
}
