package com.github.xepozz.inline_call.base.inlay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import javax.swing.JPanel

/**
 * Embed [container] inline into [editor] at [offset]. Caller MUST be on
 * EDT. Returns the [Disposable] that controls the embedded inlay so the
 * caller can dispose it on session cleanup (e.g. Delete click, file
 * close, project dispose). Returns `null` if the editor was already
 * disposed.
 *
 * Note: this used to be `suspend` and wrap the platform call in
 * `withContext(Dispatchers.EDT)`. That switch is now performed by the
 * caller (ExecutionController), which runs Phase 1 synchronously on the
 * click thread (EDT). Removing the inner withContext also dodges a
 * deadlock triggered by `withContext(Dispatchers.EDT)` from a coroutine
 * launched on `Dispatchers.Default` in test environments — see the
 * design notes on [com.github.xepozz.inline_call.base.inlay.ExecutionController].
 */
fun embedContainerIntoEditor(editor: Editor, container: JPanel, offset: Int): Disposable? {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val manager = EditorEmbeddedComponentManager.getInstance()
    val properties = EditorEmbeddedComponentManager.Properties(
        EditorEmbeddedComponentManager.ResizePolicy.any(),
        null,
        true,
        false,
        0,
        offset
    )
    val editorEx = editor as? EditorEx ?: return null
    if (editorEx.isDisposed) return null
    // addComponent returns an Inlay (a Disposable). We expose it so the
    // caller can dispose it on cleanup; otherwise the editor keeps a
    // stale reference to the embedded component and refuses to release
    // itself (Editor hasn't been released error in tests, memory leak
    // in production).
    return manager.addComponent(editorEx, container, properties)
}
