package com.github.xepozz.call.feature.shell

import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.inlay.ui.createToolbar
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.OverlayLayout

class ConsoleWrapperPanel(project: Project) : Wrapper {
    private val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    val console: ConsoleView = builder.console


    val toolbar = createToolbar()
    val toolbarWrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 5)).apply {
        isOpaque = false
        alignmentX = 1.0f
        alignmentY = 0.0f
        add(toolbar.component)
        toolbar.targetComponent = this
    }

    override val component: JComponent = JPanel(BorderLayout()).apply {
        layout = OverlayLayout(this)

        add(toolbarWrapper)
        add(console.component)
    }

}