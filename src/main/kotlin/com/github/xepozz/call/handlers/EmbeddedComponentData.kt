package com.github.xepozz.call.handlers

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

data class EmbeddedComponentData(
    val wrapper: JComponent? = null,
    val console: ConsoleView?=null,
    val disposable: Disposable= Disposer.newDisposable("EmbeddedComponentData"),
    var processHandler: ProcessHandler? = null,
    var state: ExecutionState = ExecutionState.IDLE,
    var collapsed: Boolean = false
)