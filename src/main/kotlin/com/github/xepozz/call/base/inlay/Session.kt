package com.github.xepozz.call.base.inlay

import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.handlers.ExecutionState
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import javax.swing.JPanel

internal data class Session(
    val container: JPanel?,
    var wrapper: Wrapper?,
    var state: ExecutionState = ExecutionState.IDLE,
    var processHandler: ProcessHandler? = null,
    var collapsed: Boolean = false,
    val disposable: Disposable = Disposer.newDisposable("Call.Inlay.Session")
)