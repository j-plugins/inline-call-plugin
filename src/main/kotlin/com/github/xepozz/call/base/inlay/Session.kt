package com.github.xepozz.call.base.inlay

import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.handlers.ExecutionState
import com.intellij.execution.process.ProcessHandler
import javax.swing.JPanel

data class Session(
    val container: JPanel?,
    var wrapper: Wrapper?,
    var state: ExecutionState = ExecutionState.IDLE,
    var processHandler: ProcessHandler? = null,
    var collapsed: Boolean = false,
)