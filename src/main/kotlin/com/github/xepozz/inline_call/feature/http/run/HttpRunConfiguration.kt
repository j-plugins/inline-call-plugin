// HttpRunConfiguration.kt

package com.github.xepozz.inline_call.feature.http.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import javax.swing.JComponent

class HttpRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : com.intellij.execution.configurations.RunConfigurationBase<HttpRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): HttpRunConfigurationOptions =
        super.getOptions() as HttpRunConfigurationOptions

    var url: String
        get() = options.url
        set(value) { options.url = value }

    var method: String
        get() = options.method
        set(value) { options.method = value }

    var headers: String
        get() = options.headers
        set(value) { options.headers = value }

    var body: String
        get() = options.body
        set(value) { options.body = value }

    var timeout: Int
        get() = options.timeout
        set(value) { options.timeout = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        HttpSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        HttpRunState(this, environment)
}

class HttpSettingsEditor : com.intellij.openapi.options.SettingsEditor<HttpRunConfiguration>() {

    private var url = ""
    private var method = "GET"
    private var headers = ""
    private var body = ""
    private var timeout = 30

    private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    override fun createEditor(): JComponent = panel {
        row("Method:") {
            comboBox(methods)
                .bindItem({ method }, { method = it ?: "GET" })
        }
        row("URL:") {
            textField()
                .resizableColumn()
                .bindText(::url)
        }
        row("Timeout (sec):") {
            intTextField(1..300)
                .bindIntText(::timeout)
        }
        row("Headers:") {
            textArea()
                .rows(3)
                .resizableColumn()
                .bindText(::headers)
                .comment("Format: Header-Name: value (one per line)")
        }
        row("Body:") {
            textArea()
                .rows(5)
                .resizableColumn()
                .bindText(::body)
        }
    }

    override fun applyEditorTo(s: HttpRunConfiguration) {
        s.url = url
        s.method = method
        s.headers = headers
        s.body = body
        s.timeout = timeout
    }

    override fun resetEditorFrom(s: HttpRunConfiguration) {
        url = s.url
        method = s.method
        headers = s.headers
        body = s.body
        timeout = s.timeout
    }
}