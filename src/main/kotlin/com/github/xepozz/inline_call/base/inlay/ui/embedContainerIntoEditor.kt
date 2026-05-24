package com.github.xepozz.inline_call.base.inlay.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JPanel

suspend fun embedContainerIntoEditor(editor: Editor, container: JPanel, offset: Int) {
    val manager = EditorEmbeddedComponentManager.getInstance()
    val properties = EditorEmbeddedComponentManager.Properties(
        EditorEmbeddedComponentManager.ResizePolicy.any(),
        null,
        true,
        false,
        0,
        offset
    )

    withContext(Dispatchers.EDT) {
        val editorEx = editor as? EditorEx ?: return@withContext
        if (editorEx.isDisposed) return@withContext
        manager.addComponent(editorEx, container, properties)
    }
}
