package com.github.xepozz.call.base.inlay

import com.github.xepozz.call.base.SessionStorage
import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.LanguageTextExtractor
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.extractors.AdapterLanguageExtractor
import com.github.xepozz.call.base.handlers.ExecutionState
import com.github.xepozz.call.base.inlay.ui.createResultContainer
import com.github.xepozz.call.base.inlay.ui.embedContainerIntoEditor
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.Icon
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class ExecutionInlayProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("call.implementation.inlay")
    override val name: String = "Call (Unified)"
    override val previewText: String = "// shell: echo hello\n// https://api.example.com"

    override fun createSettings(): NoSettings = NoSettings()
    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = panel { }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ) = object : FactoryInlayHintsCollector(editor) {
        val sessionStorage = SessionStorage.getInstance(file.project)

        // Pre-compute matches for the whole file once
        private val matchesByElement: Map<PsiElement, List<FeatureMatch>> = computeMatches(file)

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val matches = matchesByElement[element] ?: return true
            if (matches.isEmpty()) return true

            val project = file.project

            matches.forEach { m ->
                val offset = m.originalRange.startOffset
                val pres = buildActionsPresentation(editor, project, m)
                sink.addInlineElement(offset, false, pres, false)
            }
            return true
        }

        private fun computeMatches(file: PsiFile): Map<PsiElement, List<FeatureMatch>> {
            val project = file.project
            val allExtractors = LanguageTextExtractor.getApplicable(file).ifEmpty { return emptyMap() }

            val languageSpecificExtractors = allExtractors.filter { it !is AdapterLanguageExtractor }
            val extractors = languageSpecificExtractors.ifEmpty { allExtractors }
            println("file: $file, extractors: ${extractors.map { it.javaClass }}")

            val blocks = extractors.flatMap { it.extract(file) }.ifEmpty { return emptyMap() }
            println("blocks: ${blocks.map { it }}")

            val featureGenerators = FeatureGenerator.getApplicable(project).ifEmpty { return emptyMap() }

            val matches = mutableMapOf<PsiElement, MutableList<FeatureMatch>>()
            for (block in blocks) {
                for (featureGenerator in featureGenerators) {
                    val featureMatches = featureGenerator.match(block, project).ifEmpty { continue }

                    matches.computeIfAbsent(block.element) { mutableListOf() }.addAll(featureMatches)
                }
            }

            matches.values.forEach { list ->
                list.sortBy { it.originalRange.startOffset }
            }

            return matches
        }

        private fun buildActionsPresentation(editor: Editor, project: Project, match: FeatureMatch): InlayPresentation {
            val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == match.featureId }
            val icon: Icon? = feature?.icon
            val tooltip = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value

            val start = match.originalRange.startOffset
            val line = editor.document.getLineNumber(start)
            val lineEndOffset = editor.document.getLineEndOffset(line)
            val key = makeKey(editor, match.featureId, line)
            val session = sessionStorage.getSession(key)

            val parts = mutableListOf<InlayPresentation>()

            // Collapse/Expand appears when wrapper (container) is mounted
            if (session?.container != null) {
                val collapseIcon = if (session.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
                val collapseTooltip = if (session.collapsed) "Expand" else "Collapse"
                parts += clickableIcon(collapseIcon, collapseTooltip) {
                    toggleCollapse(session)
                    refreshInlays(editor)
                }
            }

            when (session?.state ?: ExecutionState.IDLE) {
                ExecutionState.RUNNING -> {
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

                ExecutionState.IDLE -> {
                    // Initial Run button
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = icon?.let { factory.seq(factory.icon(it), runText) } ?: runText
                    var runPres: InlayPresentation = factory.withTooltip(tooltip, withIcon)
                    runPres = factory.withCursorOnHover(runPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    runPres = factory.onClick(runPres, MouseButton.Left) { _, _ ->
                        val featureGenerator = feature ?: return@onClick
                        run(editor, project, featureGenerator, match, key, lineEndOffset)
                    }
                    parts += runPres
                }

                ExecutionState.FINISHED -> {
                    // After first launch: show Rerun icon + "Run" text
                    val rerunIcon = AllIcons.Actions.Rerun
                    val rerunTooltip = "Rerun: ${match.value}"
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = factory.seq(factory.icon(rerunIcon), runText)
                    var rerunPres: InlayPresentation = factory.withTooltip(rerunTooltip, withIcon)
                    rerunPres = factory.withCursorOnHover(rerunPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    rerunPres = factory.onClick(rerunPres, MouseButton.Left) { _, _ ->
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

        private fun <TWrapper : Wrapper> run(
            editor: Editor,
            project: Project,
            feature: FeatureGenerator<TWrapper>,
            match: FeatureMatch,
            key: String,
            lineEndOffset: Int,
        ) {
            // Ensure wrapper exists and mounted
            var current = sessionStorage.getSession(key)
            val wrapper = feature.createWrapper()

            if (current?.container == null) {
                try {
                    val container = createResultContainer()
                    embedContainerIntoEditor(editor, container, lineEndOffset)
                    mountWrapperIntoContainer(container, wrapper)

                    current = Session(container, wrapper)
                } catch (_: Throwable) {
                    current = Session(null, wrapper)
                }
                sessionStorage.putSession(key, current)
            } else {
                // Replace previous wrapper in the existing container
                val container = current.container
                val oldWrapper = current.wrapper
                if (oldWrapper != null) {
                    container.remove(oldWrapper.component)
                }
                mountWrapperIntoContainer(container, wrapper)
                current.wrapper = wrapper
            }
            current.state = ExecutionState.RUNNING

            refreshInlays(editor)

            // Execute and capture process lifecycle
            val session = current
            feature.execute(match, wrapper, project) { processHandler ->
                session.processHandler = processHandler

                processHandler?.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        session.state = ExecutionState.FINISHED
                        session.processHandler = null
                        refreshInlays(editor)
                    }
                })
            }
        }

        private fun mountWrapperIntoContainer(container: JPanel, wrapper: Wrapper) {
            invokeLater {
                container.add(wrapper.component, BorderLayout.CENTER)
                container.revalidate()
                container.repaint()
            }
        }

        private fun stop(key: String) {
            val session = sessionStorage.getSession(key) ?: return
            session.processHandler?.destroyProcess()
            session.processHandler = null
            session.state = ExecutionState.FINISHED
        }

        private fun delete(key: String) {
            val session = sessionStorage.remove(key) ?: return
            try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
            session.processHandler = null
            invokeLater {
                session.container?.parent?.remove(session.container)
            }
        }

        private fun toggleCollapse(session: Session) {
            session.collapsed = !session.collapsed
            val visible = !session.collapsed
            invokeLater { session.container?.isVisible = visible }
        }

        private fun refreshInlays(editor: Editor) {
            invokeLater {
                InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
                DaemonCodeAnalyzer.getInstance(editor.project ?: return@invokeLater).restart()
            }
        }
    }
}

fun makeKey(editor: Editor, featureId: String, line: Int): String = "${editor.hashCode()}_${featureId}_$line"

