// HttpRunConfigurationProducer.kt

package com.github.xepozz.call.http.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import java.util.regex.Pattern

class HttpRunConfigurationProducer : LazyRunConfigurationProducer<HttpRunConfiguration>() {

    companion object {
        // Поддержка расширенного синтаксиса:
        // GET https://example.com
        // POST https://example.com
        // или просто https://example.com
        private val HTTP_FULL_PATTERN = Pattern.compile(
            "(?:(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+)?(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)"
        )
    }

    override fun getConfigurationFactory(): ConfigurationFactory =
        HttpRunConfigurationType.getInstance().configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: HttpRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = context.psiLocation ?: return false
        val comment = findHttpComment(element) ?: return false
        
        val matcher = HTTP_FULL_PATTERN.matcher(comment.text)
        if (!matcher.find()) return false
        
        val method = matcher.group(1) ?: "GET"
        val url = matcher.group(2)
        
        configuration.name = "$method ${url.take(40)}${if (url.length > 40) "..." else ""}"
        configuration.method = method
        configuration.url = url
        
        sourceElement.set(comment)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: HttpRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val element = context.psiLocation ?: return false
        val comment = findHttpComment(element) ?: return false
        
        val matcher = HTTP_FULL_PATTERN.matcher(comment.text)
        if (!matcher.find()) return false
        
        val method = matcher.group(1) ?: "GET"
        val url = matcher.group(2)
        
        return configuration.url == url && configuration.method == method
    }

    private fun findHttpComment(element: PsiElement): PsiComment? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiComment && HTTP_FULL_PATTERN.matcher(current.text).find()) {
                return current
            }
            current = current.parent
        }
        return null
    }
}