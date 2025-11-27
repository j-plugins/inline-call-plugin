package com.github.xepozz.call.implementation.inlay

import com.github.xepozz.call.implementation.api.FeatureGenerator
import com.github.xepozz.call.implementation.api.FeatureMatch
import com.github.xepozz.call.implementation.api.LanguageTextExtractor
import com.github.xepozz.call.implementation.api.WrapperFactory
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.Cursor
import javax.swing.Icon

/**
 * Unified InlayHintsProvider that uses the new extensible mechanism:
 *  - LanguageTextExtractor EP to collect text blocks
 *  - FeatureGenerator EP to match features within blocks
 *  - WrapperFactory EP to create output wrappers on Run
 *
 * Minimal Phase 1: renders a single Run action per match.
 */
@Suppress("UnstableApiUsage")
class ExecutionInlayProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("call.implementation.inlay")
    override val name: String = "Call (Unified)"
    override val previewText: String = "// shell: echo hello\n// https://api.example.com"

    override fun createSettings(): NoSettings = NoSettings()
    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = com.intellij.ui.dsl.builder.panel { }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector = object : FactoryInlayHintsCollector(editor) {

        // Pre-compute matches for the whole file once
        private val matchesByElement: Map<PsiElement, List<FeatureMatch>> = computeMatches(file)

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val matches = matchesByElement[element] ?: return true
            if (matches.isEmpty()) return true

            matches.forEach { m ->
                val offset = m.originalRange.startOffset
                val pres = buildRunPresentation(editor, editor.project, m)
                sink.addInlineElement(offset, false, pres, false)
            }
            return true
        }

        private fun computeMatches(file: PsiFile): Map<PsiElement, List<FeatureMatch>> {
            val project = file.project
            val extractors = LanguageTextExtractor.EP_NAME.extensionList.filter { it.isApplicable(file) }
            if (extractors.isEmpty()) return emptyMap()

            val blocks = extractors.flatMap { it.extract(file) }
            if (blocks.isEmpty()) return emptyMap()

            val features = FeatureGenerator.EP_NAME.extensionList.filter { it.isEnabled(project) }
            if (features.isEmpty()) return emptyMap()

            val matches = mutableMapOf<PsiElement, MutableList<FeatureMatch>>()
            for (b in blocks) {
                for (f in features) {
                    val ms = f.match(b, project)
                    if (ms.isNotEmpty()) {
                        matches.computeIfAbsent(b.element) { mutableListOf() }.addAll(ms)
                    }
                }
            }

            // Sort matches by start offset for stable rendering
            matches.values.forEach { list ->
                list.sortBy { it.originalRange.startOffset }
            }

            return matches
        }

        private fun buildRunPresentation(editor: Editor, project: Project?, match: FeatureMatch): InlayPresentation {
            val feature = FeatureGenerator.EP_NAME.extensionList.firstOrNull { it.id == match.featureId }
            val icon: Icon? = feature?.icon
            val tooltip = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value

            val clickable = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
            val withIcon = icon?.let { factory.seq(factory.icon(it), clickable) } ?: clickable
            val withTooltip = factory.withTooltip(tooltip, withIcon)
            // Use hand cursor to look like a link on hover
            val withHandCursor = factory.withCursorOnHover(withTooltip, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

            return factory.onClick(withHandCursor, MouseButton.Left) { _, _ ->
                if (project == null || feature == null) return@onClick
                val wrapper = WrapperFactory.EP_NAME.extensionList.firstOrNull { it.supports(feature.id) }?.create(match)
                    ?: return@onClick

                // Show wrapper component in a popup near the editor as a primary container for results
                try {
                    val popup = JBPopupFactory.getInstance()
                        .createComponentPopupBuilder(wrapper.component, null)
                        .setRequestFocus(false)
                        .setResizable(true)
                        .setMovable(true)
                        .setTitle("${feature.tooltipPrefix}")
                        .createPopup()
                    // Show near caret/editor best position
                    popup.showInBestPositionFor(editor)
                } catch (_: Throwable) {
                    // Fallback: ignore UI errors, still execute feature
                }

                // Execute feature; ProcessHandler management may be added later (Phase 2)
                feature.execute(match, wrapper, project) { /* ignore */ }
            }
        }
    }
}
