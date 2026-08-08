// HttpRunConfiguration.kt

package com.github.xepozz.inline_call.feature.http.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
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

/**
 * Mutable snapshot the Kotlin UI DSL binds against. Translated to/from
 * [HttpRunConfiguration] in [HttpSettingsEditor.applyEditorTo] /
 * [HttpSettingsEditor.resetEditorFrom].
 */
private data class HttpUiModel(
    var url: String = "",
    var method: String = "GET",
    var headers: String = "",
    var body: String = "",
    var timeout: Int = 30,
)

class HttpSettingsEditor : SettingsEditor<HttpRunConfiguration>() {

    private val model = HttpUiModel()
    private lateinit var panel: DialogPanel

    private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    override fun createEditor(): JComponent {
        panel = panel {
            row("Method:") {
                comboBox(methods)
                    .bindItem({ model.method }, { model.method = it ?: "GET" })
            }
            row("URL:") {
                textField()
                    .resizableColumn()
                    .bindText(model::url)
            }
            row("Timeout (sec):") {
                intTextField(1..300)
                    .bindIntText(model::timeout)
            }
            row("Headers:") {
                textArea()
                    .rows(3)
                    .resizableColumn()
                    .bindText(model::headers)
                    .comment("Format: Header-Name: value (one per line)")
            }
            row("Body:") {
                textArea()
                    .rows(5)
                    .resizableColumn()
                    .bindText(model::body)
            }
        }
        return panel
    }

    override fun applyEditorTo(s: HttpRunConfiguration) {
        // Flush UI -> model first, then copy model -> options.
        panel.apply()
        s.url = model.url
        s.method = model.method
        s.headers = model.headers
        s.body = model.body
        s.timeout = model.timeout
    }

    override fun resetEditorFrom(s: HttpRunConfiguration) {
        // Copy options -> model first, then refresh UI from model.
        model.url = s.url
        model.method = s.method
        model.headers = s.headers
        model.body = s.body
        model.timeout = s.timeout
        if (::panel.isInitialized) {
            panel.reset()
        }
    }
}
