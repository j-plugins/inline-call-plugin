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
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
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
import com.intellij.icons.AllIcons
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer

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

    private data class Session(
        val container: JPanel?,
        var wrapper: Wrapper?,
        var state: com.github.xepozz.call.handlers.ExecutionState = com.github.xepozz.call.handlers.ExecutionState.IDLE,
        var processHandler: ProcessHandler? = null,
        var collapsed: Boolean = false,
        val disposable: Disposable = Disposer.newDisposable("Call.Inlay.Session")
    )

    // key: editorId + featureId + line
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

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
                val pres = buildActionsPresentation(editor, editor.project, m)
                sink.addInlineElement(offset, false, pres, false)
            }
            return true
        }

        private fun computeMatches(file: PsiFile): Map<PsiElement, List<FeatureMatch>> {
            val project = file.project
            val applicable = LanguageTextExtractor.getApplicable(file)
            if (applicable.isEmpty()) return emptyMap()

            // Prefer language-specific extractors over the generic fallback (AdapterLanguageExtractor)
            val specific = applicable.filter { it !is com.github.xepozz.call.implementation.extractors.AdapterLanguageExtractor }
            val extractors = specific.ifEmpty { applicable }
            println("file: $file, extractors: ${extractors.map { it.javaClass }}")

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

        private fun buildActionsPresentation(editor: Editor, project: Project?, match: FeatureMatch): InlayPresentation {
            val feature = FeatureGenerator.EP_NAME.extensionList.firstOrNull { it.id == match.featureId }
            val icon: Icon? = feature?.icon
            val tooltip = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value

            // Compute session key
            val start = match.originalRange.startOffset
            val line = editor.document.getLineNumber(start)
            val lineEndOffset = editor.document.getLineEndOffset(line)
            val key = makeKey(editor, match.featureId, line)
            val session = sessions[key]

            val parts = mutableListOf<InlayPresentation>()

            // Collapse/Expand appears when wrapper (container) is mounted
            if (session?.container != null) {
                val collapseIcon = if (session.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
                val collapseTooltip = if (session.collapsed) "Expand" else "Collapse"
                parts += clickableIcon(collapseIcon, collapseTooltip) {
                    toggleCollapse(key)
                    refreshInlays(editor)
                }
            }

            when (session?.state ?: com.github.xepozz.call.handlers.ExecutionState.IDLE) {
                com.github.xepozz.call.handlers.ExecutionState.RUNNING -> {
                    // Stop button
                    parts += clickableIcon(AllIcons.Actions.Suspend, "Stop") {
                        stop(key)
                        refreshInlays(editor)
                    }
                    // Delete button — reset state to never-run and remove UI
                    parts += clickableIcon(AllIcons.General.Remove, "Delete") {
                        delete(key)
                        refreshInlays(editor)
                    }
                }
                com.github.xepozz.call.handlers.ExecutionState.IDLE -> {
                    // Initial Run button
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = icon?.let { factory.seq(factory.icon(it), runText) } ?: runText
                    var runPres: InlayPresentation = factory.withTooltip(tooltip, withIcon)
                    runPres = factory.withCursorOnHover(runPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    runPres = factory.onClick(runPres, MouseButton.Left) { _, _ ->
                        if (project == null) return@onClick
                        val feat = feature ?: return@onClick
                        run(editor, project, feat, match, key, lineEndOffset)
                    }
                    parts += runPres
                }
                com.github.xepozz.call.handlers.ExecutionState.FINISHED -> {
                    // After first launch: show Rerun icon + "Run" text
                    val rerunIcon = AllIcons.Actions.Rerun
                    val rerunTooltip = "Rerun: ${match.value}"
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = factory.seq(factory.icon(rerunIcon), runText)
                    var rerunPres: InlayPresentation = factory.withTooltip(rerunTooltip, withIcon)
                    rerunPres = factory.withCursorOnHover(rerunPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    rerunPres = factory.onClick(rerunPres, MouseButton.Left) { _, _ ->
                        if (project == null) return@onClick
                        val feat = feature ?: return@onClick
                        run(editor, project, feat, match, key, lineEndOffset)
                    }
                    parts += rerunPres

                    // Also allow to delete/reset after successful completion
                    parts += clickableIcon(AllIcons.General.Remove, "Delete") {
                        delete(key)
                        refreshInlays(editor)
                    }
                }
            }

            return if (parts.size == 1) parts.first() else factory.seq(*parts.toTypedArray())
        }

        private fun clickableIcon(icon: Icon, tooltip: String, onClick: () -> Unit): InlayPresentation {
            var p: InlayPresentation = factory.icon(icon)
            p = factory.inset(p, 2, 2, 0, 0)
            p = factory.withTooltip(tooltip, p)
            p = factory.withCursorOnHover(p, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
            p = factory.onClick(p, MouseButton.Left) { _, _ -> onClick() }
            return p
        }

        private fun run(
            editor: Editor,
            project: Project,
            feature: FeatureGenerator,
            match: FeatureMatch,
            key: String,
            lineEndOffset: Int,
        ) {
            // Ensure wrapper exists and mounted
            val current = sessions[key]
            val wrapper = WrapperFactory.EP_NAME.extensionList.firstOrNull { it.supports(feature.id) }?.create(match)
                ?: return

            if (current?.container == null) {
                try {
                    val container = embedContainerIntoEditor(editor, lineEndOffset)
                    invokeLater {
                        container.removeAll()
                        container.add(wrapper.component, BorderLayout.CENTER)
                        container.revalidate()
                        container.repaint()
                    }
                    sessions[key] = Session(container, wrapper).also { it.state = com.github.xepozz.call.handlers.ExecutionState.RUNNING }
                } catch (_: Throwable) {
                    // If embedding fails, still execute without container
                    sessions[key] = Session(null, wrapper).also { it.state = com.github.xepozz.call.handlers.ExecutionState.RUNNING }
                }
            } else {
                // Replace previous wrapper in the existing container
                val container = current.container
                val oldWrapper = current.wrapper
                if (container != null) {
                    invokeLater {
                        container.removeAll()
                        container.add(wrapper.component, BorderLayout.CENTER)
                        container.revalidate()
                        container.repaint()
                    }
                }
                try { oldWrapper?.dispose() } catch (_: Throwable) {}
                current.wrapper = wrapper
                current.state = com.github.xepozz.call.handlers.ExecutionState.RUNNING
            }

            refreshInlays(editor)

            // Execute and capture process lifecycle
            val sess = sessions[key] ?: return
            feature.execute(match, wrapper, project) { ph ->
                sess.processHandler = ph
                // When process terminates, mark as FINISHED
                ph?.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                    override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                        sess.state = com.github.xepozz.call.handlers.ExecutionState.FINISHED
                        sess.processHandler = null
                        refreshInlays(editor)
                    }
                })
            }
        }

        private fun stop(key: String) {
            val s = sessions[key] ?: return
            s.processHandler?.destroyProcess()
            s.processHandler = null
            s.state = com.github.xepozz.call.handlers.ExecutionState.FINISHED
        }

        private fun delete(key: String) {
            val s = sessions.remove(key) ?: return
            try { s.processHandler?.destroyProcess() } catch (_: Throwable) {}
            s.processHandler = null
            invokeLater {
                s.container?.parent?.remove(s.container)
            }
            try { s.wrapper?.dispose() } catch (_: Throwable) {}
            Disposer.dispose(s.disposable)
        }

        private fun toggleCollapse(key: String) {
            val s = sessions[key] ?: return
            s.collapsed = !s.collapsed
            val visible = !s.collapsed
            invokeLater { s.container?.isVisible = visible }
        }

        private fun refreshInlays(editor: Editor) {
            invokeLater {
                InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(editor.project ?: return@invokeLater).restart()
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
