package com.github.xepozz.inline_call.base.lifecycle

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent

/**
 * Watches PSI mutations and evicts sessions whose anchor element has been
 * deleted (e.g. the user removes the `// https://...` comment while a
 * request is in flight).
 *
 * The "user edited the URL" case is handled by the stale-key sweep at the
 * end of `computeMatches` instead — the anchor still points to the same
 * `PsiComment`, but the new `valueHash` produces a new key and the old
 * one no longer has a current match.
 *
 * Registered programmatically by
 * [com.github.xepozz.inline_call.base.lifecycle.SessionLifecycleStarter]
 * with [SessionStorage] as the [com.intellij.openapi.Disposable] parent.
 */
class SessionPsiChangeListener(private val project: Project) : PsiTreeChangeAdapter() {

    override fun childRemoved(event: PsiTreeChangeEvent) = reap(event)
    override fun childReplaced(event: PsiTreeChangeEvent) = reap(event)
    override fun childrenChanged(event: PsiTreeChangeEvent) = reap(event)

    private fun reap(event: PsiTreeChangeEvent) {
        val file = event.file ?: event.parent?.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (file.project != project) return

        val storage = SessionStorage.getInstance(project)
        val sessions = storage.sessionsForFile(virtualFile.url)
        if (sessions.isEmpty()) return

        for ((key, session) in sessions) {
            // isAlive() handles its own read action.
            if (!session.isAlive()) {
                storage.remove(key)
                terminateAndDispose(session)
            }
        }
    }

    private fun terminateAndDispose(session: Session) {
        try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
        session.processHandler = null
        val container = session.container
        if (container != null) {
            invokeLater {
                container.parent?.remove(container)
            }
            session.container = null
        }
    }
}
