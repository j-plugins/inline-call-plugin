package com.github.xepozz.call.implementation.api

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Common API contracts for the new extensible architecture.
 * This is a minimal Phase 1 subset to keep changes small and safe.
 */

/**
 * Maps between normalized text offsets and original PSI text offsets.
 * For Phase 1 we keep identity mapping; extractors may upgrade it later.
 */
interface OffsetMapping {
    fun toOriginal(normalizedOffset: Int): Int
    fun toNormalized(originalOffset: Int): Int

    object Identity : OffsetMapping {
        override fun toOriginal(normalizedOffset: Int) = normalizedOffset
        override fun toNormalized(originalOffset: Int) = originalOffset
    }
}

/**
 * A block of text extracted from PSI that can be processed by features.
 */
data class ExtractedBlock(
    val element: PsiElement,
    val originalRange: TextRange,
    val originalText: String,
    val normalizedText: String = originalText,
    val mapping: OffsetMapping = OffsetMapping.Identity,
)

/**
 * Extractor of text blocks from a file or element (language-specific).
 */
interface LanguageTextExtractor {
    fun isApplicable(file: PsiFile): Boolean
    fun extract(file: PsiFile): List<ExtractedBlock>

    companion object {
        val EP_NAME: ExtensionPointName<LanguageTextExtractor> =
            ExtensionPointName.create("com.github.xepozz.call.languageTextExtractor")
    }
}

/**
 * A matched feature occurrence within an extracted block.
 */
data class FeatureMatch(
    val featureId: String,
    val block: ExtractedBlock,
    val value: String,
    val normalizedRange: TextRange,
    val originalRange: TextRange,
)

/**
 * Feature generator that finds matches and can execute them.
 */
interface FeatureGenerator {
    val id: String
    val icon: Icon
    val tooltipPrefix: String

    fun isEnabled(project: Project): Boolean = true

    /**
     * Produce zero or more matches for a given block.
     */
    fun match(block: ExtractedBlock, project: Project): List<FeatureMatch>

    /**
     * Execute the given match and stream output to the provided wrapper.
     */
    fun execute(match: FeatureMatch, wrapper: Wrapper, project: Project, onProcessCreated: (ProcessHandler?) -> Unit = {})

    companion object {
        val EP_NAME: ExtensionPointName<FeatureGenerator> =
            ExtensionPointName.create("com.github.xepozz.call.feature")
    }
}

/**
 * Simple output wrapper API. In Phase 1, a console-based implementation is enough.
 */
interface Wrapper {
    val component: JComponent
    val console: ConsoleView
    fun dispose()
}

/**
 * Factory for wrappers per feature.
 */
interface WrapperFactory {
    fun supports(featureId: String): Boolean
    fun create(match: FeatureMatch): Wrapper

    companion object {
        val EP_NAME: ExtensionPointName<WrapperFactory> =
            ExtensionPointName.create("com.github.xepozz.call.wrapperFactory")
    }
}
