package com.github.xepozz.inline_call.base.inlay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import javax.swing.SwingConstants

fun createToolbar(): ActionToolbar {
    val copyAction = object : AnAction("Copy", "Copy output", AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            val content = "console.text"
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
    }

    val actionGroup = DefaultActionGroup(copyAction)

    val toolbar = ActionManager.getInstance()
        .createActionToolbar("ConsoleInlay", actionGroup, false)
        .apply {
            layoutStrategy = ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY
            orientation = SwingConstants.VERTICAL
            component.isOpaque = false
        }
    return toolbar
}