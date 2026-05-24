package com.github.xepozz.inline_call.base.lifecycle

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reaps sessions when the user closes the last editor for the underlying
 * file.
 *
 * If the file is still open in another split / window of the same project
 * (`source.getAllEditors(file)` is non-empty) this is a split-close, not a
 * file-close, and we do nothing — the session has to remain so the
 * surviving pane keeps controlling the running process.
 *
 * Registered as a project-level listener in `META-INF/plugin.xml`:
 *
 *     <projectListeners>
 *         <listener
 *             class="...SessionLifecycleListener"
 *             topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
 *     </projectListeners>
 *
 * Project-level listeners are instantiated by the platform with the
 * [Project] passed via constructor injection.
 */
class SessionLifecycleListener(private val project: Project) : FileEditorManagerListener {

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        // Split close: file is still open somewhere — keep the session intact.
        if (source.getAllEditors(file).isNotEmpty()) return

        val storage = SessionStorage.getInstance(project)
        val sessions = storage.sessionsForFile(file.url)
        if (sessions.isEmpty()) return

        for ((key, session) in sessions) {
            when (session.state) {
                ExecutionState.RUNNING -> {
                    terminateProcess(session)
                    session.state = ExecutionState.FINISHED
                    detachContainer(session)
                }
                ExecutionState.FINISHED -> {
                    detachContainer(session)
                }
                ExecutionState.IDLE -> {
                    // Never ran — nothing to preserve.
                    storage.remove(key)
                    detachContainer(session)
                }
            }
        }
    }

    // fileOpened is intentionally a no-op: the inlay pass that follows
    // file open will rebuild the buttons via the stable key, and the
    // session container is recreated lazily on the first Rerun click
    // (see ExecutionInlayProvider.run() — needsContainer branch).

    private fun terminateProcess(session: Session) {
        try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
        session.processHandler = null
    }

    private fun detachContainer(session: Session) {
        val container = session.container ?: return
        // The Editor that hosted the container is being disposed; drop
        // the panel from the Swing tree on EDT and null it out so the
        // next Run/Rerun rebuilds it.
        invokeLater {
            container.parent?.remove(container)
        }
        session.container = null
    }
}
