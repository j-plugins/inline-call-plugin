package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.api.Wrapper
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.inlay.ui.createResultContainer
import com.github.xepozz.inline_call.base.inlay.ui.embedContainerIntoEditor
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Project-scoped service that performs run / stop / delete / toggle-collapse
 * for inline-call feature matches and refreshes the declarative inlay pass
 * afterwards. Kept independent from any specific inlay provider so the same
 * logic can be reused from [InlayActionHandler] implementations or from
 * future entry points (toolbar action, run configurations, etc.).
 */
@Service(Service.Level.PROJECT)
class ExecutionController(private val project: Project) {

    private val sessionStorage get() = SessionStorage.getInstance(project)

    fun run(
        editor: Editor,
        feature: FeatureGenerator<*>,
        match: FeatureMatch,
        key: String,
        lineEndOffset: Int,
    ) {
        @Suppress("UNCHECKED_CAST")
        runTyped(editor, feature as FeatureGenerator<Wrapper>, match, key, lineEndOffset)
    }

    private fun <TWrapper : Wrapper> runTyped(
        editor: Editor,
        feature: FeatureGenerator<TWrapper>,
        match: FeatureMatch,
        key: String,
        lineEndOffset: Int,
    ) {
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

    fun stop(editor: Editor, key: String) {
        val session = sessionStorage.getSession(key) ?: return
        session.processHandler?.destroyProcess()
        session.processHandler = null
        session.state = ExecutionState.FINISHED
        refreshInlays(editor)
    }

    fun delete(editor: Editor, key: String) {
        val session = sessionStorage.remove(key) ?: return
        try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
        session.processHandler = null
        invokeLater {
            session.container?.parent?.remove(session.container)
            refreshInlays(editor)
        }
    }

    fun toggleCollapse(editor: Editor, key: String) {
        val session = sessionStorage.getSession(key) ?: return
        session.collapsed = !session.collapsed
        val visible = !session.collapsed
        invokeLater {
            session.container?.isVisible = visible
            refreshInlays(editor)
        }
    }

    private fun mountWrapperIntoContainer(container: JPanel, wrapper: Wrapper) {
        invokeLater {
            container.add(wrapper.component, BorderLayout.CENTER)
            container.revalidate()
            container.repaint()
        }
    }

    /**
     * Forces the declarative inlay pass to re-run for the given editor.
     *
     * Our SessionStorage state lives off-PSI, so the daemon would otherwise
     * skip the pass: the file modification stamp has not changed between
     * RUN / STOP / DELETE transitions. [DeclarativeInlayHintsPassFactory.scheduleRecompute]
     * resets the cached stamp; [DaemonCodeAnalyzer.restart] then re-schedules
     * the pass for the affected file.
     */
    private fun refreshInlays(editor: Editor) {
        invokeLater {
            DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psiFile != null) {
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            } else {
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ExecutionController = project.service()
    }
}
