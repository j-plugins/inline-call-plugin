package com.github.xepozz.call.feature.http

import com.github.xepozz.call.base.handlers.ExecutionHandler
import com.github.xepozz.call.base.api.ExtractedBlock
import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern
import javax.swing.Icon

/**
 * Feature adapter that delegates matching and execution to existing HttpExecutionHandler.
 */
class HttpFeatureAdapter : FeatureGenerator, ExecutionHandler {
    override val id: String = "http"
    override val pattern: Pattern = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)")
    override val icon: Icon = AllIcons.General.Web
    override val tooltipPrefix: String = "Fetch"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = findMatches(block.normalizedText)
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
        console.print("GET $value\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Connecting...\n\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
        onProcessCreated(null)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(value))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build()

                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                    invokeLater {
//                        if (!Disposer.isDisposed(disposable)) {
                        printResponse(console, response)
//                        }
                    }
                } catch (e: Exception) {
                    invokeLater {
//                        if (!Disposer.isDisposed(disposable)) {
                        console.print("[Error: ${e.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
//                        }
                    }
                }
            }
        })
    }

    private fun printResponse(console: ConsoleView, response: HttpResponse<String>) {
        val statusType = when (response.statusCode()) {
            in 200..299 -> ConsoleViewContentType.SYSTEM_OUTPUT
            in 300..399 -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.ERROR_OUTPUT
        }

        console.print("HTTP ${response.statusCode()}\n", statusType)

        console.print("\n--- Headers ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        response.headers().map().entries.take(10).forEach { (k, v) ->
            console.print("$k: ${v.firstOrNull()}\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
        }

        console.print("\n--- Body ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        console.print(response.body().take(5000), ConsoleViewContentType.NORMAL_OUTPUT)

        if (response.body().length > 5000) {
            console.print("\n\n[Truncated...]", ConsoleViewContentType.LOG_WARNING_OUTPUT)
        }
    }
}