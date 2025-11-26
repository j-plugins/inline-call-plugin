package com.github.xepozz.call.handlers

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
abstract class ExecutionInlayProvider : InlayHintsProvider<NoSettings> {

    companion object {
        val activeComponents = ConcurrentHashMap<String, EmbeddedComponentData>()
    }

    override val key = SettingsKey<NoSettings>("execution.inlay.hints")
    override val name = "Execution Preview"
    override val previewText = "// shell: echo hello\n// https://api.example.com"

    override fun createSettings() = NoSettings()
    override fun createConfigurable(settings: NoSettings) = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = panel { }
    }

    abstract fun getHandler(): ExecutionHandler

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ) = object : FactoryInlayHintsCollector(editor) {
        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (element !is PsiComment) return true

            val text = element.text
            val project = element.project

            val handler = getHandler()
            handler.findMatches(text).forEach { match ->
                val offset = element.textOffset + match.offset

                sink.addInlineElement(
                    offset,
                    false,
                    createPresentation(handler.icon, "${handler.tooltipPrefix}: ${match.value}") {
                        toggle(handler, match.value, element, editor, project)
                    },
                    false
                )
            }

            return true
        }

        private fun createPresentation(
            icon: Icon,
            tooltip: String,
            onClick: () -> Unit
        ): InlayPresentation {
            var p: InlayPresentation = factory.icon(icon)
            p = factory.inset(p, 2, 4, 0, 0)
            p = factory.withTooltip(tooltip, p)
            p = factory.referenceOnHover(p) { _, _ -> onClick() }

            return p
        }
    }

    private fun toggle(
        handler: ExecutionHandler,
        value: String,
        comment: PsiElement,
        editor: Editor,
        project: Project
    ) {
        val key = makeKey(editor, comment)

        if (closeIfExists(key)) return

        val (console, disposable) = createConsole(project)

        embedComponent(editor, comment, key, console.component, console, disposable)

        handler.execute(value, console, disposable, project)
    }

    private fun createConsole(project: Project): Pair<ConsoleView, Disposable> {
        val disposable = Disposer.newDisposable("ExecutionInlay")
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply { setViewer(true) }
            .console

        Disposer.register(disposable, console)
        return console to disposable
    }

    private fun embedComponent(
        editor: Editor,
        comment: PsiElement,
        key: String,
        component: JComponent,
        console: ConsoleView?,
        disposable: Disposable
    ) {
        invokeLater {
            val line = editor.document.getLineNumber(comment.textOffset)
            val offset = editor.document.getLineEndOffset(line)

            val wrapper = JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1),
                    JBUI.Borders.empty(4)
                )
                background = JBColor.background()
                add(component, BorderLayout.CENTER)
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

            manager.addComponent(editor as EditorEx, wrapper, properties)

            activeComponents[key] = EmbeddedComponentData(wrapper, console, disposable)
        }
    }

    private fun makeKey(editor: Editor, comment: PsiElement): String {
        val line = editor.document.getLineNumber(comment.textOffset)
        return "${editor.hashCode()}_$line"
    }

    private fun closeIfExists(key: String): Boolean {
        activeComponents[key]?.let { data ->
            invokeLater {
                data.component.parent?.remove(data.component)
                Disposer.dispose(data.disposable)
            }
            activeComponents.remove(key)
            return true
        }
        return false
    }
}