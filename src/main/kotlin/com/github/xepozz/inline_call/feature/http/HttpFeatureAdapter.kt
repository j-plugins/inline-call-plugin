package com.github.xepozz.inline_call.feature.http

import com.github.xepozz.inline_call.base.api.ExtractedBlock
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.util.RegexpMatcher
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.swing.Icon

/**
 * Feature adapter that delegates matching and execution to existing HttpExecutionHandler.
 */
class HttpFeatureAdapter(val project: Project) : FeatureGenerator<HttpWrapperPanel> {
    override val id: String = "http"
    override val icon: Icon = AllIcons.General.Web
    override val tooltipPrefix: String = "Fetch"

    val matcher = RegexpMatcher(Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)"))

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = matcher.findMatches(block.text)
        val base = block.originalRange.startOffset
        return matches.map { m ->
            val startOriginal = base + block.mapping.toOriginal(m.offset)
            val endOriginal = base + block.mapping.toOriginal(m.offset + m.value.length)
            assert(endOriginal - startOriginal >= m.value.length) {
                "endOriginal($endOriginal) - startOriginal($startOriginal) < value.length(${m.value.length})"
            }
            FeatureMatch(
                featureId = id,
                block = block,
                value = m.value,
                normalizedRange = TextRange(m.offset, m.offset + m.value.length),
                originalRange = TextRange(startOriginal, endOriginal),
            )
        }
    }

    override suspend fun execute(
        match: FeatureMatch,
        wrapper: HttpWrapperPanel,
        project: Project,
    ): ProcessHandler? {
        val value = match.value
        val console = wrapper.console

        console.print("GET $value\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Connecting...\n\n", ConsoleViewContentType.LOG_INFO_OUTPUT)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(value))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val terminated = AtomicBoolean(false)
        val handler = object : ProcessHandler() {
            override fun detachIsDefault(): Boolean = false
            override fun getProcessInput() = null

            override fun destroyProcessImpl() {
                // The owning coroutine cancellation interrupts the IO thread.
                // We only flip the terminated state and notify.
                if (terminated.compareAndSet(false, true)) {
                    notifyProcessTerminated(143)
                }
            }

            override fun detachProcessImpl() {
                if (terminated.compareAndSet(false, true)) {
                    notifyProcessDetached()
                }
            }

            fun complete(exitCode: Int) {
                if (terminated.compareAndSet(false, true)) {
                    notifyProcessTerminated(exitCode)
                }
            }
        }
        handler.startNotify()

        try {
            val response = runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
            withContext(NonCancellable + Dispatchers.EDT) {
                console.clear()
                printResponse(console, response)
            }
            handler.complete(0)
        } catch (ce: CancellationException) {
            // Cancellation: the caller (Stop button / project close) drives UI feedback.
            handler.complete(143)
            throw ce
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.EDT) {
                console.clear()
                console.print("[Error: ${t.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            handler.complete(-1)
        }
        return handler
    }

    private fun printResponse(console: ConsoleView, response: HttpResponse<String>) {
        response.headers().map().entries.take(10).forEach { (k, v) ->
            console.print("$k: ${v.firstOrNull()}\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
        }

        console.print("\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        console.print(response.body(), ConsoleViewContentType.NORMAL_OUTPUT)
    }

    override fun createWrapper() = HttpWrapperPanel(project)
}
