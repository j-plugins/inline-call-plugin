package com.github.xepozz.inline_call.base.lifecycle

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Covers the split-pane close case: closing one of N editors for the same
 * file does not fire `FileEditorManagerListener.fileClosed` — only
 * `EditorFactoryListener.editorReleased` fires per released [com.intellij.openapi.editor.Editor].
 *
 * If the released editor was the *last* one for the file (no other open
 * editor in [FileEditorManager]), we behave like a full file close:
 * terminate any running process, dispose containers, and remove IDLE
 * entries.
 *
 * Otherwise we drop only the panel that was anchored to this specific
 * released editor (panels are 1-per-session today, so typically nothing to
 * do — the surviving editor's panel is untouched).
 *
 * Wired programmatically in [com.github.xepozz.inline_call.base.lifecycle.SessionLifecycleStarter]
 * with [SessionStorage] as the [com.intellij.openapi.Disposable] parent
 * so unregistration is automatic on project close.
 */
class SessionEditorFactoryListener(private val project: Project) : EditorFactoryListener {

    override fun editorReleased(event: EditorFactoryEvent) {
        val released = event.editor
        if (released.project != null && released.project != project) return

        val document = released.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return

        val fem = FileEditorManager.getInstance(project)
        val remainingEditors = fem.getAllEditors(virtualFile)
        if (remainingEditors.isNotEmpty()) {
            // Other panes still hold the file; nothing to clean up here.
            // (Per-editor panel cleanup is currently a no-op because
            // sessions hold a single shared container — see
            // ExecutionInlayProvider.run().)
            return
        }

        val storage = SessionStorage.getInstance(project)
        val sessions = storage.sessionsForFile(virtualFile.url)
        if (sessions.isEmpty()) return

        for ((key, session) in sessions) {
            when (session.state) {
                ExecutionState.RUNNING -> {
                    terminateProcess(session)
                    session.state = ExecutionState.FINISHED
                    detachContainer(session)
                }
                ExecutionState.FINISHED -> detachContainer(session)
                ExecutionState.IDLE -> {
                    storage.remove(key)
                    detachContainer(session)
                }
            }
        }
    }

    private fun terminateProcess(session: Session) {
        try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
        session.processHandler = null
    }

    private fun detachContainer(session: Session) {
        val container = session.container ?: return
        invokeLater {
            container.parent?.remove(container)
        }
        session.container = null
    }
}
