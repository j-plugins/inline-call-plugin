package com.github.xepozz.call.handlers

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import javax.swing.JComponent

data class EmbeddedComponentData(
    val component: JComponent,
    val console: ConsoleView?,
    val disposable: Disposable
)

