package com.github.xepozz.call.feature.shell

import com.github.xepozz.call.base.api.ExtractedBlock
import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.handlers.ExecutionHandler
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.util.regex.Pattern
import javax.swing.Icon

/**
 * Feature adapter that delegates matching and execution to existing ShellExecutionHandler.
 */
class ShellFeatureAdapter : FeatureGenerator, ExecutionHandler {
    override val id: String = "shell"
    override val pattern: Pattern = Pattern.compile("shell:\\s*(.+)")
    override val icon: Icon = AllIcons.Actions.Execute
    override val tooltipPrefix: String = "Run"

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = findMatches(block.text)
        val base = block.originalRange.startOffset
        return matches.map { m ->
            val startOriginal = base + block.mapping.toOriginal(m.offset)
            val endOriginal = startOriginal + m.value.length
            FeatureMatch(
                featureId = id,
                block = block,
                value = m.value,
                normalizedRange = TextRange(m.offset, m.offset + m.value.length),
                originalRange = TextRange(startOriginal, endOriginal),
            )
        }
    }

    override fun execute(
        match: FeatureMatch,
        wrapper: Wrapper,
        project: Project,
        onProcessCreated: (ProcessHandler?) -> Unit
    ) {
        val value = match.value
        val console = wrapper.console
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