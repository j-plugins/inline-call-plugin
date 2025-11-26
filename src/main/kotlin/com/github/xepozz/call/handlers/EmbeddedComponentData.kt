package com.github.xepozz.call.handlers

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import javax.swing.JComponent

data class EmbeddedComponentData(
    val wrapper: JComponent,
    val console: ConsoleView?,
    val disposable: Disposable,
    var processHandler: ProcessHandler? = null,
    var state: ExecutionState = ExecutionState.IDLE,
    var collapsed: Boolean = false
)