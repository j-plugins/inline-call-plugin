package com.github.xepozz.inline_call.feature.http

import com.github.xepozz.inline_call.base.api.Wrapper
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class HttpWrapperPanel(project: Project) : Wrapper {
    val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

    override val component: JComponent = console.component
}
