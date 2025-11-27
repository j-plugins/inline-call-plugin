package com.github.xepozz.call.base.wrappers

import com.github.xepozz.call.base.api.Wrapper
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * Minimal console-based wrapper implementation for Phase 1.
 */
class ConsoleWrapper(project: Project) : Wrapper {
    private val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    override val console: ConsoleView = builder.console
    override val component: JComponent = console.component

    override fun dispose() {
        console.dispose()
    }
}
