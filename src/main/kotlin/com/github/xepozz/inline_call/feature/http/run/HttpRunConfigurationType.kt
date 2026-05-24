// HttpRunConfigurationType.kt

package com.github.xepozz.inline_call.feature.http.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import javax.swing.Icon

class HttpRunConfigurationType : ConfigurationType {

    companion object {
        // Persistence contract: this value is written into workspace.xml as
        // `type="..."` and is part of the plugin's public surface once
        // shipped. Do NOT rename without a migration shim.
        const val ID = "HttpInlayRunConfiguration"

        fun getInstance(): HttpRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(HttpRunConfigurationType::class.java)
    }

    override fun getId(): String = ID
    override fun getDisplayName(): String = "HTTP Request"
    override fun getConfigurationTypeDescription(): @Nls(capitalization = Nls.Capitalization.Sentence) String? {
        return "Run HTTP request from comment"
    }
    override fun getIcon(): Icon = AllIcons.General.Web

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(HttpConfigurationFactory(this))
}

class HttpConfigurationFactory(type: ConfigurationType) : com.intellij.execution.configurations.ConfigurationFactory(type) {

    companion object {
        // Persistence contract: written into workspace.xml as `factoryName="..."`.
        // Must be unique within `HttpRunConfigurationType`. Do NOT rename.
        const val FACTORY_ID = "HTTP"
    }

    override fun getId(): String = FACTORY_ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        HttpRunConfiguration(project, this, "HTTP")

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        HttpRunConfigurationOptions::class.java
}