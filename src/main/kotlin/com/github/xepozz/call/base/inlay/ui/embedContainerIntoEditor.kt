package com.github.xepozz.call.base.inlay.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import javax.swing.JPanel

fun embedContainerIntoEditor(editor: Editor, container: JPanel, offset: Int) {
    val manager = EditorEmbeddedComponentManager.getInstance()
    val properties = EditorEmbeddedComponentManager.Properties(
        EditorEmbeddedComponentManager.ResizePolicy.any(),
        null,
        true,
        false,
        0,
        offset
    )

    invokeLater {
        manager.addComponent(editor as EditorEx, container, properties)
    }
}