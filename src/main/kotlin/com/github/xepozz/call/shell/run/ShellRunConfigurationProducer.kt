// ShellRunConfigurationProducer.kt

package com.github.xepozz.call.shell.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import java.util.regex.Pattern

class ShellRunConfigurationProducer : LazyRunConfigurationProducer<ShellRunConfiguration>() {

    companion object {
        private val SHELL_PATTERN = Pattern.compile("shell:\\s*(.+)")
    }

    override fun getConfigurationFactory(): ConfigurationFactory =
        ShellRunConfigurationType.getInstance().configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: ShellRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = context.psiLocation ?: return false
        val comment = findShellComment(element) ?: return false
        
        val matcher = SHELL_PATTERN.matcher(comment.text)
        if (!matcher.find()) return false
        
        val command = matcher.group(1).trim()
        
        configuration.name = "Shell: ${command.take(30)}${if (command.length > 30) "..." else ""}"
        configuration.command = command
        configuration.workingDirectory = context.project.basePath ?: ""
        
        sourceElement.set(comment)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: ShellRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val element = context.psiLocation ?: return false
        val comment = findShellComment(element) ?: return false
        
        val matcher = SHELL_PATTERN.matcher(comment.text)
        if (!matcher.find()) return false
        
        return configuration.command == matcher.group(1).trim()
    }

    private fun findShellComment(element: PsiElement): PsiComment? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiComment && SHELL_PATTERN.matcher(current.text).find()) {
                return current
            }
            current = current.parent
        }
        return null
    }
}