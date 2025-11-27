// ShellRunConfiguration.kt

package com.github.xepozz.call.feature.shell.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextField

class ShellRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<ShellRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): ShellRunConfigurationOptions =
        super.getOptions() as ShellRunConfigurationOptions

    var command: String
        get() = options.command
        set(value) { options.command = value }

    var workingDirectory: String
        get() = options.workingDirectory
        set(value) { options.workingDirectory = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        ShellSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return ShellRunState(this, environment)
    }
}

class ShellRunState(
    private val configuration: ShellRunConfiguration,
    private val environment: ExecutionEnvironment
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val commandLine = GeneralCommandLine("/bin/sh", "-c", configuration.command)
            .withRedirectErrorStream(true)
        
        if (configuration.workingDirectory.isNotBlank()) {
            commandLine.withWorkDirectory(configuration.workingDirectory)
        } else {
            commandLine.withWorkDirectory(environment.project.basePath)
        }

        val processHandler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(processHandler)
        return processHandler
    }
}

class ShellSettingsEditor : SettingsEditor<ShellRunConfiguration>() {
    
    private val commandField = JTextField()
    private val workingDirField = TextFieldWithBrowseButton()

    override fun createEditor(): JComponent = panel {
        row("Command:") {
            cell(commandField).resizableColumn()
        }
        row("Working directory:") {
            cell(workingDirField).resizableColumn()
        }
    }

    override fun applyEditorTo(s: ShellRunConfiguration) {
        s.command = commandField.text
        s.workingDirectory = workingDirField.text
    }

    override fun resetEditorFrom(s: ShellRunConfiguration) {
        commandField.text = s.command
        workingDirField.text = s.workingDirectory
    }
}