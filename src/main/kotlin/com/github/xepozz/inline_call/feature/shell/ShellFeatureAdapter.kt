package com.github.xepozz.inline_call.feature.shell

import com.github.xepozz.inline_call.base.api.ExtractedBlock
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.util.RegexpMatcher
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.util.regex.Pattern
import javax.swing.Icon

/**
 * Feature adapter that runs a shell command via [OSProcessHandler].
 *
 * The implementation is intentionally callback-based (no coroutines)
 * because the inlay click path runs on EDT and any suspension between
 * EDT and the service scope was found to silently swallow the work
 * (see the design notes on
 * [com.github.xepozz.inline_call.base.inlay.ExecutionController]).
 *
 * `Process.start()` is fast — running it on the caller's thread (EDT)
 * is acceptable for a one-off click. The actual process IO happens
 * off-thread inside OSProcessHandler's notification machinery.
 */
class ShellFeatureAdapter(val project: Project) : FeatureGenerator<ConsoleWrapperPanel> {
    override val id: String = "shell"
    override val icon: Icon = AllIcons.Actions.Execute
    override val tooltipPrefix: String = "Run"

    val matcher = RegexpMatcher(Pattern.compile("shell:\\s*(.+)"))

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = matcher.findMatches(block.text)
        val base = block.originalRange.startOffset
        return matches.map { m ->
            val startOriginal = base + block.mapping.toOriginal(m.offset)
            val endOriginal = base + block.mapping.toOriginal(m.offset + m.value.length)
            assert(endOriginal - startOriginal >= m.value.length) {
                "endOriginal($endOriginal) - startOriginal($startOriginal) < value.length(${m.value.length})"
            }
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
        wrapper: ConsoleWrapperPanel,
        project: Project,
        onProcessCreated: (ProcessHandler?) -> Unit,
    ) {
        val value = match.value
        val console = wrapper.console

        try {
            val commandLine = GeneralCommandLine("/bin/sh", "-c", value)
                .withRedirectErrorStream(true)
            val processHandler = OSProcessHandler(commandLine).also {
                ProcessTerminatedListener.attach(it)
            }
            console.attachToProcess(processHandler)
            onProcessCreated(processHandler)
            processHandler.startNotify()
        } catch (e: Exception) {
            ApplicationManager.getApplication().invokeLater {
                console.print("\n[Error: ${e.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            onProcessCreated(null)
        }
    }

    override fun createWrapper() = ConsoleWrapperPanel(project)
}
