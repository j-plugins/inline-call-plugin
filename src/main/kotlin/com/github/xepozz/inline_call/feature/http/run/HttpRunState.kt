// HttpRunState.kt

package com.github.xepozz.inline_call.feature.http.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.concurrent.thread

class HttpRunState(
    private val configuration: HttpRunConfiguration,
    private val environment: ExecutionEnvironment
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .console

        val processHandler = HttpProcessHandler(configuration, console)
        console.attachToProcess(processHandler)

        return DefaultExecutionResult(console, processHandler)
    }
}

class HttpProcessHandler(
    private val configuration: HttpRunConfiguration,
    private val console: com.intellij.execution.ui.ConsoleView
) : com.intellij.execution.process.ProcessHandler() {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(configuration.timeout.toLong()))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun startNotify() {
        super.startNotify()
        
        thread {
            executeRequest()
        }
    }

    private fun executeRequest() {
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
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            printResponse(response)

        } catch (e: Exception) {
            printToConsole("\n[Error: ${e.message}]\n", ConsoleViewContentType.ERROR_OUTPUT)
        } finally {
            notifyProcessTerminated(0)
        }
    }

    private fun printResponse(response: HttpResponse<String>) {
        val statusType = when (response.statusCode()) {
            in 200..299 -> ConsoleViewContentType.SYSTEM_OUTPUT
            in 300..399 -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            else -> ConsoleViewContentType.ERROR_OUTPUT
        }

        printToConsole("HTTP ${response.statusCode()}\n", statusType)

        // Headers
        printToConsole("\n--- Response Headers ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        response.headers().map().forEach { (key, values) ->
            values.forEach { value ->
                printToConsole("$key: $value\n", ConsoleViewContentType.LOG_INFO_OUTPUT)
            }
        }

        // Body
        printToConsole("\n--- Response Body ---\n", ConsoleViewContentType.LOG_DEBUG_OUTPUT)
        
        val body = response.body()
        val contentType = response.headers().firstValue("content-type").orElse("")
        
        // Pretty print JSON
        val displayBody = if (contentType.contains("json")) {
            tryPrettyPrintJson(body)
        } else {
            body
        }
        
        printToConsole(displayBody, ConsoleViewContentType.NORMAL_OUTPUT)

        if (body.length > 10000) {
            printToConsole("\n\n[Response truncated, total size: ${body.length} bytes]", ConsoleViewContentType.LOG_WARNING_OUTPUT)
        }

        printToConsole("\n", ConsoleViewContentType.NORMAL_OUTPUT)
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

    private fun printToConsole(text: String, contentType: ConsoleViewContentType) {
        com.intellij.openapi.application.invokeLater {
            console.print(text, contentType)
        }
    }

    override fun destroyProcessImpl() {
        notifyProcessTerminated(1)
    }

    override fun detachProcessImpl() {
        notifyProcessTerminated(0)
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}