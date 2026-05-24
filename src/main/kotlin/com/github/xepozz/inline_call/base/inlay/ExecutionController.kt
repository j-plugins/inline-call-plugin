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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Project-scoped service that performs run / stop / delete / toggle-collapse
 * for inline-call feature matches and refreshes the declarative inlay pass
 * afterwards. Kept independent from any specific inlay provider so the same
 * logic can be reused from [com.intellij.codeInsight.hints.declarative.InlayActionHandler]
 * implementations or from future entry points (toolbar action, run configurations, etc.).
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
        virtualFile: VirtualFile?,
        occurrenceIndex: Int,
        anchorElement: PsiElement?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typedFeature = feature as FeatureGenerator<Wrapper>
        val newJob = sessionStorage.cs.launch(CoroutineName("inline-call:$key")) {
            runImpl(editor, typedFeature, match, key, lineEndOffset, virtualFile, occurrenceIndex, anchorElement)
        }
        sessionStorage.replaceJob(key, newJob)?.cancel()
    }

    private suspend fun <TWrapper : Wrapper> runImpl(
        editor: Editor,
        feature: FeatureGenerator<TWrapper>,
        match: FeatureMatch,
        key: String,
        lineEndOffset: Int,
        virtualFile: VirtualFile?,
        occurrenceIndex: Int,
        anchorElement: PsiElement?,
    ) {
        val wrapper = feature.createWrapper()
        val vfUrl = virtualFile?.url ?: ""
        val valueHash = SessionStorage.hashValue(match.value)
        val anchor: SmartPsiElementPointer<PsiElement>? =
            if (anchorElement != null && anchorElement.isValid)
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchorElement)
            else null

        // Phase 1: ensure container exists and is mounted; replace wrapper.
        val session = withContext(Dispatchers.EDT) {
            var current = sessionStorage.getSession(key)

            // Container may be null because (a) never mounted, or (b) the
            // previous editor was disposed on file close (container exists
            // but its parent is null — rebuild and re-mount).
            val needsContainer = current?.container == null || current.container?.parent == null

            if (needsContainer) {
                val container = try {
                    val c = createResultContainer()
                    embedContainerIntoEditor(editor, c, lineEndOffset)
                    mountWrapperIntoContainer(c, wrapper)
                    c
                } catch (_: Throwable) {
                    null
                }
                if (current == null) {
                    current = Session(
                        virtualFileUrl = vfUrl,
                        featureId = match.featureId,
                        valueHash = valueHash,
                        occurrenceIndex = occurrenceIndex,
                        anchor = anchor,
                        container = container,
                        wrapper = wrapper,
                    )
                } else {
                    current.container = container
                    current.wrapper = wrapper
                    if (current.anchor == null) current.anchor = anchor
                }
                sessionStorage.putSession(key, current)
            } else {
                val container = current!!.container!!
                val oldWrapper = current.wrapper
                if (oldWrapper != null) {
                    container.remove(oldWrapper.component)
                }
                mountWrapperIntoContainer(container, wrapper)
                current.wrapper = wrapper
                if (current.anchor == null) current.anchor = anchor
            }
            current.state = ExecutionState.RUNNING
            refreshInlaysOnEdt(editor)
            current
        }

        // Phase 2: execute. feature.execute manages its own dispatcher hops.
        val handler = feature.execute(match, wrapper, project)
        session.processHandler = handler

        if (handler == null) {
            withContext(NonCancellable + Dispatchers.EDT) {
                session.state = ExecutionState.FINISHED
                refreshInlaysOnEdt(editor)
            }
            return
        }

        // Phase 3: bridge ProcessListener -> CompletableDeferred so the
        // coroutine awaits termination without blocking a thread.
        val done = CompletableDeferred<Unit>()
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                done.complete(Unit)
            }
        })
        try {
            done.await()
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                session.processHandler = null
                session.state = ExecutionState.FINISHED
                refreshInlaysOnEdt(editor)
            }
        }
    }

    fun stop(editor: Editor, key: String) {
        val session = sessionStorage.getSession(key) ?: return
        session.job?.cancel()
        session.processHandler?.destroyProcess()
        session.processHandler = null
        session.state = ExecutionState.FINISHED
        refreshInlays(editor)
    }

    fun delete(editor: Editor, key: String) {
        val session = sessionStorage.remove(key) ?: return
        try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
        session.processHandler = null
        sessionStorage.cs.launch(Dispatchers.EDT) {
            session.container?.parent?.remove(session.container)
            refreshInlaysOnEdt(editor)
        }
    }

    fun toggleCollapse(editor: Editor, key: String) {
        val session = sessionStorage.getSession(key) ?: return
        session.collapsed = !session.collapsed
        val visible = !session.collapsed
        sessionStorage.cs.launch(Dispatchers.EDT) {
            session.container?.isVisible = visible
            refreshInlaysOnEdt(editor)
        }
    }

    private fun mountWrapperIntoContainer(container: JPanel, wrapper: Wrapper) {
        // Caller is expected to already be on the EDT.
        container.add(wrapper.component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
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
    fun refreshInlays(editor: Editor) {
        sessionStorage.cs.launch(Dispatchers.EDT) {
            refreshInlaysOnEdt(editor)
        }
    }

    private fun refreshInlaysOnEdt(editor: Editor) {
        DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (psiFile != null) {
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
        } else {
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    companion object {
        fun getInstance(project: Project): ExecutionController = project.service()
    }
}
