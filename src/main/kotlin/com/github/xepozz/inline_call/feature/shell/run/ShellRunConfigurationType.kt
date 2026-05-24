// ShellRunConfigurationType.kt

package com.github.xepozz.inline_call.feature.shell.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.Icon

class ShellRunConfigurationType : ConfigurationType {

    companion object {
        // Persistence contract: this value is written into workspace.xml as
        // `type="..."` and is part of the plugin's public surface once
        // shipped. Do NOT rename without a migration shim.
        const val ID = "ShellInlayRunConfiguration"

        fun getInstance(): ShellRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(ShellRunConfigurationType::class.java)
    }

    override fun getId(): String = ID
    override fun getDisplayName(): String = "Shell Command"
    override fun getConfigurationTypeDescription(): @Nls(capitalization = Nls.Capitalization.Sentence) String? {
        return "Run shell command from comment"
    }

    override fun getIcon(): Icon = AllIcons.Actions.Execute

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(ShellConfigurationFactory(this))
}

class ShellConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    companion object {
        // Persistence contract: written into workspace.xml as `factoryName="..."`.
        // Must be unique within `ShellRunConfigurationType`. Do NOT rename.
        const val FACTORY_ID = "Shell"
    }

    override fun getId(): String = FACTORY_ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        ShellRunConfiguration(project, this, "Shell")

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        ShellRunConfigurationOptions::class.java
}