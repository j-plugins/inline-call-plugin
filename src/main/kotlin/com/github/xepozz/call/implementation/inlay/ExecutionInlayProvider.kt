package com.github.xepozz.call.implementation.inlay

import com.github.xepozz.call.implementation.api.FeatureGenerator
import com.github.xepozz.call.implementation.api.FeatureMatch
import com.github.xepozz.call.implementation.api.LanguageTextExtractor
import com.github.xepozz.call.implementation.api.Wrapper
import com.github.xepozz.call.implementation.api.WrapperFactory
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.Cursor
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.BorderFactory
import java.awt.Dimension

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

    private data class ContainerHolder(
        val container: JPanel,
        var wrapper: Wrapper
    )

    // key: editorId + featureId + line (end offset position)
    private val containers = java.util.concurrent.ConcurrentHashMap<String, ContainerHolder>()

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
                val newWrapper = WrapperFactory.EP_NAME.extensionList.firstOrNull { it.supports(feature.id) }?.create(match)
                    ?: return@onClick

                // Compute a stable key for this feature at the target line in this editor
                val start = match.originalRange.startOffset
                val line = editor.document.getLineNumber(start)
                val lineEndOffset = editor.document.getLineEndOffset(line)
                val key = makeKey(editor, feature.id, line)

                // Reuse embedded container per feature+line; replace inner component
                val holder = containers[key]
                if (holder == null) {
                    // First time: create container and embed into editor
                    try {
                        val container = embedContainerIntoEditor(editor, lineEndOffset)
                        invokeLater {
                            container.removeAll()
                            container.add(newWrapper.component, BorderLayout.CENTER)
                            container.revalidate()
                            container.repaint()
                        }
                        containers[key] = ContainerHolder(container, newWrapper)
                    } catch (_: Throwable) {
                        // ignore UI embedding errors, still execute feature
                    }
                } else {
                    // Replace previous wrapper component only for this feature
                    val oldWrapper = holder.wrapper
                    invokeLater {
                        holder.container.removeAll()
                        holder.container.add(newWrapper.component, BorderLayout.CENTER)
                        holder.container.revalidate()
                        holder.container.repaint()
                    }
                    // Dispose old wrapper to free resources
                    try { oldWrapper.dispose() } catch (_: Throwable) {}
                    holder.wrapper = newWrapper
                }

                // Execute feature after (re)showing wrapper
                feature.execute(match, newWrapper, project) { /* ignore */ }
            }
        }
    }
}

private fun embedContainerIntoEditor(editor: Editor, offset: Int): JPanel {
    val wrapper = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(4)
        )
        background = JBColor.background()
        preferredSize = Dimension(700, 250)
    }

    val manager = EditorEmbeddedComponentManager.getInstance()
    val properties = EditorEmbeddedComponentManager.Properties(
        EditorEmbeddedComponentManager.ResizePolicy.any(),
        null,
        true,
        false,
        0,
        offset
    )

    invokeLater {
        manager.addComponent(editor as EditorEx, wrapper, properties)
    }

    return wrapper
}

private fun makeKey(editor: Editor, featureId: String, line: Int): String = "${editor.hashCode()}_${featureId}_$line"
