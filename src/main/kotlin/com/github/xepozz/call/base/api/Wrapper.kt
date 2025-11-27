package com.github.xepozz.call.base.api

import com.intellij.execution.ui.ConsoleView
import javax.swing.JComponent

/**
 * Simple output wrapper API. In Phase 1, a console-based implementation is enough.
 */
interface Wrapper {
    val component: JComponent
    val console: ConsoleView
    fun dispose()
}