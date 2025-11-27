package com.github.xepozz.call.base.wrappers

import com.github.xepozz.call.base.api.Wrapper
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/**
 * Minimal console-based wrapper implementation for Phase 1.
 */
class ToolbarWrapper(project: Project) : Wrapper {
    val copyAction = object : AnAction("Copy", "Copy output", AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            val content = "console.text"
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
    }

    val actionGroup = DefaultActionGroup(copyAction)

    val toolbar = ActionManager.getInstance()
        .createActionToolbar("ConsoleInlay", actionGroup, true)
        .apply {
            component.isOpaque = false
        }

    private val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    override val console: ConsoleView = builder.console
    override val component: JComponent = toolbar.component

    override fun dispose() {
    }
}
