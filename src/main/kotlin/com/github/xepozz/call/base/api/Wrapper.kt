package com.github.xepozz.call.base.api

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import javax.swing.JComponent

/**
 * Simple output wrapper API. In Phase 1, a console-based implementation is enough.
 */
interface Wrapper : Disposable {
    val component: JComponent
    val console: ConsoleView
}