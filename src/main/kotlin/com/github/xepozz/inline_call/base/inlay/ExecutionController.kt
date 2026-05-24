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
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Project-scoped service that performs run / stop / delete / toggle-collapse
 * for inline-call feature matches and refreshes the declarative inlay pass
 * afterwards.
 *
 * Design notes (post-debugging):
 *
 * - `run()` is invoked from the IDE click thread (EDT). Phase 1 (Swing
 *   component mounting + state transition to RUNNING) runs synchronously
 *   on the caller's EDT — no dispatcher switch, no suspension. This is
 *   intentional: tests revealed that `withContext(Dispatchers.EDT)` from
 *   a coroutine launched on `Dispatchers.Default` (the service scope's
 *   default) does not reliably resume its body in `BasePlatformTestCase`
 *   environments due to modality/dispatch interactions. Avoiding the
 *   switch entirely makes Run actually run.
 *
 * - Phase 2 (`feature.execute`) is launched in `SessionStorage.cs` so it
 *   can do its own IO/async work and is cancelled with the session.
 *
 * - Phase 3 (process termination → UI refresh) is wired through
 *   [ProcessListener.processTerminated]; the listener marshals back to
 *   EDT via `ApplicationManager.invokeLater` for the state flip and
 *   inlay refresh.
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
        // Cancel any in-flight job for the same key first.
        sessionStorage.getSession(key)?.job?.cancel()
        // Phase 1: synchronous on EDT — mount the result container and
        // flip state to RUNNING so the inlay redraws as "Stop / Delete".
        val session = preparePhase1(editor, typedFeature, match, key, lineEndOffset, virtualFile, occurrenceIndex, anchorElement)
        // Phase 2: launch feature.execute on the service scope. The Job
        // is stored on the session so stop()/delete() can cancel it.
        val job = sessionStorage.cs.launch(CoroutineName("inline-call:$key")) {
            val handler = try {
                typedFeature.execute(match, session.wrapper!!, project)
            } catch (t: Throwable) {
                null
            }
            session.processHandler = handler
            if (handler == null) {
                finishOnEdt(session, editor)
            } else {
                attachTerminationListener(handler, session, editor)
            }
        }
        session.job = job
    }

    private fun <TWrapper : Wrapper> preparePhase1(
        editor: Editor,
        feature: FeatureGenerator<TWrapper>,
        match: FeatureMatch,
        key: String,
        lineEndOffset: Int,
        virtualFile: VirtualFile?,
        occurrenceIndex: Int,
        anchorElement: PsiElement?,
    ): com.github.xepozz.inline_call.base.inlay.Session {
        val wrapper = feature.createWrapper()
        val vfUrl = virtualFile?.url ?: ""
        val valueHash = SessionStorage.hashValue(match.value)
        val anchor: SmartPsiElementPointer<PsiElement>? =
            if (anchorElement != null && anchorElement.isValid)
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(anchorElement)
            else null

        var current = sessionStorage.getSession(key)
        val needsContainer = current?.container == null || current.container?.parent == null

        if (needsContainer) {
            var embedded: Disposable? = null
            val container = try {
                val c = createResultContainer()
                embedded = embedContainerIntoEditor(editor, c, lineEndOffset)
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
                    embeddedInlay = embedded,
                )
            } else {
                // Dispose any previous embedded inlay before overwriting.
                current.embeddedInlay?.let { Disposer.dispose(it) }
                current.container = container
                current.wrapper = wrapper
                current.embeddedInlay = embedded
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
        return current
    }

    private fun attachTerminationListener(
        handler: ProcessHandler,
        session: Session,
        editor: Editor,
    ) {
        // Race-safe: handler may already be terminated by the time we attach.
        if (handler.isProcessTerminated) {
            finishOnEdt(session, editor)
            return
        }
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                finishOnEdt(session, editor)
            }
        })
    }

    private fun finishOnEdt(session: Session, editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            session.processHandler = null
            session.state = ExecutionState.FINISHED
            refreshInlaysOnEdt(editor)
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
        ApplicationManager.getApplication().invokeLater {
            // Dispose the embedded inlay so the editor can release its
            // reference. Removing from Swing parent is not enough —
            // EditorEmbeddedComponentManager tracks the inlay separately.
            session.embeddedInlay?.let { try { Disposer.dispose(it) } catch (_: Throwable) {} }
            session.embeddedInlay = null
            session.container?.parent?.remove(session.container)
            session.container = null
            refreshInlaysOnEdt(editor)
        }
    }

    fun toggleCollapse(editor: Editor, key: String) {
        val session = sessionStorage.getSession(key) ?: return
        session.collapsed = !session.collapsed
        val visible = !session.collapsed
        ApplicationManager.getApplication().invokeLater {
            session.container?.isVisible = visible
            refreshInlaysOnEdt(editor)
        }
    }

    private fun mountWrapperIntoContainer(container: JPanel, wrapper: Wrapper) {
        // Caller is expected to already be on EDT.
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
        if (ApplicationManager.getApplication().isDispatchThread) {
            refreshInlaysOnEdt(editor)
        } else {
            ApplicationManager.getApplication().invokeLater { refreshInlaysOnEdt(editor) }
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
