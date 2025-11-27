package com.github.xepozz.call.feature.http

import com.github.xepozz.call.base.api.ExtractedBlock
import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.util.RegexpMatcher
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.swing.Icon

/**
 * Feature adapter that delegates matching and execution to existing HttpExecutionHandler.
 */
class HttpFeatureAdapter : FeatureGenerator {
    override val id: String = "http"
    override val icon: Icon = AllIcons.General.Web
    override val tooltipPrefix: String = "Fetch"

    val matcher = RegexpMatcher(Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)"))

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = matcher.findMatches(block.text)
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
        // Build request
        val request = HttpRequest.newBuilder()
            .uri(URI.create(value))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        // Create lightweight ProcessHandler to drive UI state transitions
        var future: CompletableFuture<HttpResponse<String>>? = null
        val terminated = AtomicBoolean(false)
        val handler = object : ProcessHandler() {
            override fun detachIsDefault(): Boolean = false
            override fun getProcessInput() = null

            override fun destroyProcessImpl() {
                // Cancel ongoing request if any
                if (terminated.compareAndSet(false, true)) {
                    future?.cancel(true)
                    notifyProcessTerminated(0)
                }
            }

            override fun detachProcessImpl() {
                if (terminated.compareAndSet(false, true)) {
                    future?.cancel(true)
                    notifyProcessDetached()
                }
            }

            // Expose safe termination method to outer scope
            fun complete(exitCode: Int) {
                if (terminated.compareAndSet(false, true)) {
                    notifyProcessTerminated(exitCode)
                }
            }
        }

        onProcessCreated(handler)
        handler.startNotify()

        // Execute asynchronously; on completion signal process termination
        future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                if (throwable != null) {
                    invokeLater {
                        console.print("[Error: ${throwable.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                    handler.complete(-1)
                } else if (response != null) {
                    invokeLater {
                        printResponse(console, response)
                    }
                    handler.complete(0)
                }
            }
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