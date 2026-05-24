// ShellRunConfiguration.kt

package com.github.xepozz.inline_call.feature.shell.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

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

/**
 * Mutable snapshot the Kotlin UI DSL binds against. Translated to/from
 * [ShellRunConfiguration] in [ShellSettingsEditor.applyEditorTo] /
 * [ShellSettingsEditor.resetEditorFrom].
 */
private data class ShellUiModel(
    var command: String = "",
    var workingDirectory: String = "",
)

class ShellSettingsEditor : SettingsEditor<ShellRunConfiguration>() {

    private val model = ShellUiModel()
    private lateinit var panel: DialogPanel

    override fun createEditor(): JComponent {
        panel = panel {
            row("Command:") {
                textField()
                    .resizableColumn()
                    .bindText(model::command)
            }
            row("Working directory:") {
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                )
                    .resizableColumn()
                    .bindText(model::workingDirectory)
            }
        }
        return panel
    }

    override fun applyEditorTo(s: ShellRunConfiguration) {
        panel.apply()
        s.command = model.command
        s.workingDirectory = model.workingDirectory
    }

    override fun resetEditorFrom(s: ShellRunConfiguration) {
        model.command = s.command
        model.workingDirectory = s.workingDirectory
        if (::panel.isInitialized) {
            panel.reset()
        }
    }
}
