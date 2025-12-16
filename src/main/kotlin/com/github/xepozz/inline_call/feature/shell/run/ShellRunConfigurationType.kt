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
        const val ID = "ShellInlayRunConfiguration"
        
        fun getInstance(): ShellRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(ShellRunConfigurationType::class.java)
    }

    override fun getId(): String = ID
    override fun getDisplayName(): String = "Shell Command"
    override fun getConfigurationTypeDescription(): @Nls(capitalization = Nls.Capitalization.Sentence) String? {
        return "configuration type description"
    }

    override fun getIcon(): Icon = AllIcons.Actions.Execute

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(ShellConfigurationFactory(this))
}

class ShellConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    
    override fun getId(): String = ShellRunConfigurationType.ID
    
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        ShellRunConfiguration(project, this, "Shell")

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        ShellRunConfigurationOptions::class.java
}