// HttpRunConfigurationType.kt

package com.github.xepozz.call.feature.http.run

import com.intellij.execution.configurations.*
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import org.jetbrains.annotations.Nls
import javax.swing.Icon

class HttpRunConfigurationType : ConfigurationType {
    
    companion object {
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
    
    override fun getId(): String = HttpRunConfigurationType.ID
    
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        HttpRunConfiguration(project, this, "HTTP")

    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        HttpRunConfigurationOptions::class.java
}