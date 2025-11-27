package com.github.xepozz.call.handlers

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.regex.Pattern
import javax.swing.Icon

class ShellExecutionHandler : ExecutionInlayProvider(), ExecutionHandler {

    override val pattern: Pattern = Pattern.compile("shell:\\s*(.+)")
    override val icon: Icon = AllIcons.Actions.Execute
    override val tooltipPrefix: String = "Run"

    override fun getHandler() = this

    override fun execute(
        value: String,
        console: ConsoleView,
        project: Project,
        onProcessCreated: (ProcessHandler?) -> Unit,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Executing", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val commandLine = GeneralCommandLine("/bin/sh", "-c", value)
                        .withRedirectErrorStream(true)

                    val processHandler = OSProcessHandler(commandLine)
                    ProcessTerminatedListener.attach(processHandler)

                    invokeLater {
//                        if (!Disposer.isDisposed(disposable)) {
                        console.attachToProcess(processHandler)
                        onProcessCreated(processHandler)
                        processHandler.startNotify()
//                        }
                    }

                } catch (e: Exception) {
                    invokeLater {
                        onProcessCreated(null)
//                        if (!Disposer.isDisposed(disposable)) {
                        console.print("\n[Error: ${e.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
//                        }
                    }
                }
            }
        })
    }
}