package com.github.xepozz.inline_call.base.lifecycle

import com.github.xepozz.inline_call.base.SessionStorage
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiManager

/**
 * Wires the programmatic listeners that cannot easily be declared in
 * `plugin.xml` because they need a [com.intellij.openapi.Disposable]
 * parent (so unregistration is automatic on project close).
 *
 * The [SessionStorage] project service is used as the disposable parent;
 * when the project is disposed it disposes the storage, which in turn
 * disposes our listener registrations.
 *
 * Project-level `FileEditorManagerListener` is declared in `plugin.xml`
 * via `<projectListeners>` and does NOT need this starter.
 */
class SessionLifecycleStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        val storage = SessionStorage.getInstance(project)

        EditorFactory.getInstance().addEditorFactoryListener(
            SessionEditorFactoryListener(project),
            storage,
        )

        PsiManager.getInstance(project).addPsiTreeChangeListener(
            SessionPsiChangeListener(project),
            storage,
        )
    }
}
