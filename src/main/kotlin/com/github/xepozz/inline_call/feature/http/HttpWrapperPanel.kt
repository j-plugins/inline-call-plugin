package com.github.xepozz.inline_call.feature.http

import com.github.xepozz.inline_call.base.api.Wrapper
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent
import javax.swing.JTabbedPane

class HttpWrapperPanel(project: Project) : Wrapper {
    private val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    val bodyConsole: ConsoleView = builder.console
    val headersConsole: ConsoleView = builder.console

    val console: ConsoleView = builder.console

    override val component: JComponent = JBTabbedPane(JTabbedPane.TOP).apply {
        addTab("Raw", console.component)
        addTab("Headers", headersConsole.component)
        addTab("Body", bodyConsole.component)
//        addTab("Preview", bodyConsole.component)
    }
}