// HttpRunState.kt

package com.github.xepozz.inline_call.feature.http.run

import com.github.xepozz.inline_call.base.SessionStorage
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class HttpRunState(
    private val configuration: HttpRunConfiguration,
    private val environment: ExecutionEnvironment
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .console

        val processHandler = HttpProcessHandler(configuration, console, environment.project)
        console.attachToProcess(processHandler)

        return DefaultExecutionResult(console, processHandler)
    }
}

class HttpProcessHandler(
    private val configuration: HttpRunConfiguration,
    private val console: com.intellij.execution.ui.ConsoleView,
    private val project: Project,
) : com.intellij.execution.process.ProcessHandler() {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(configuration.timeout.toLong()))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val terminated = AtomicBoolean(false)
    @Volatile
    private var job: Job? = null

    override fun startNotify() {
        super.startNotify()

        val cs = SessionStorage.getInstance(project).cs
        job = cs.launch(CoroutineName("inline-call:http-run:${configuration.url}")) {
            executeRequest()
        }
    }

    private suspend fun executeRequest() {
        try {
            printToConsole("${configuration.method} ${configuration.url}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            printToConsole("Connecting...\n\n", ConsoleViewContentType.LOG_INFO_OUTPUT)

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(configuration.url))
                .timeout(Duration.ofSeconds(configuration.timeout.toLong()))

            // Method + body
            val bodyPublisher = if (configuration.body.isNotBlank()) {
                HttpRequest.BodyPublishers.ofString(configuration.body)
            } else {
                HttpRequest.BodyPublishers.noBody()
            }

            when (configuration.method) {
                "GET" -> requestBuilder.GET()
                "POST" -> requestBuilder.POST(bodyPublisher)
                "PUT" -> requestBuilder.PUT(bodyPublisher)
                "PATCH" -> requestBuilder.method("PATCH", bodyPublisher)
                "DELETE" -> requestBuilder.DELETE()
                "HEAD" -> requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                "OPTIONS" -> requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            }

            // Headers
            parseHeaders(configuration.headers).forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val request = requestBuilder.build()
            val response = runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }

            withContext(NonCancellable + Dispatchers.EDT) {
                printResponseOnEdt(response)
            }

            completeOnce(0)
        } catch (ce: CancellationException) {
            completeOnce(143)
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.EDT) {
                console.print("\n[Error: ${e.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            completeOnce(0)
        }
    }

    private fun printResponseOnEdt(response: HttpResponse<String>) {
        val statusType = when (response.statusCode()) {
            in 200..299 -> ConsoleViewContentType.SYSTEM_OUTPUT
            in 300..399 -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.ERROR_OUTPUT
        }

        console.print("HTTP ${response.statusCode()}\n", statusType)

        // Headers
        console.print("\n--- Response Headers ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        response.headers().map().forEach { (key, values) ->
            values.forEach { value ->
                console.print("$key: $value\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
            }
        }

        // Body
        console.print("\n--- Response Body ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)

        val body = response.body()
        val contentType = response.headers().firstValue("content-type").orElse("")

        // Pretty print JSON
        val displayBody = if (contentType.contains("json")) {
            tryPrettyPrintJson(body)
        } else {
            body
        }

        console.print(displayBody, ConsoleViewContentType.NORMAL_OUTPUT)

        if (body.length > 10000) {
            console.print("\n\n[Response truncated, total size: ${body.length} bytes]", ConsoleViewContentType.LOG_WARNING_OUTPUT)
        }

        console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun tryPrettyPrintJson(json: String): String {
        return try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val element = com.google.gson.JsonParser.parseString(json)
            gson.toJson(element)
        } catch (e: Exception) {
            json
        }
    }

    private fun parseHeaders(headersString: String): Map<String, String> {
        return headersString.lines()
            .filter { it.contains(":") }
            .associate { line ->
                val (key, value) = line.split(":", limit = 2)
                key.trim() to value.trim()
            }
    }

    private suspend fun printToConsole(text: String, contentType: ConsoleViewContentType) {
        withContext(Dispatchers.EDT) {
            console.print(text, contentType)
        }
    }

    private fun completeOnce(exitCode: Int) {
        if (terminated.compareAndSet(false, true)) {
            notifyProcessTerminated(exitCode)
        }
    }

    override fun destroyProcessImpl() {
        job?.cancel()
        completeOnce(143)
    }

    override fun detachProcessImpl() {
        job?.cancel()
        completeOnce(0)
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
