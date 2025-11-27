// ShellRunConfigurationOptions.kt

package com.github.xepozz.call.feature.shell.run

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class ShellRunConfigurationOptions : RunConfigurationOptions() {
    
    private val _command: StoredProperty<String?> = string("")
        .provideDelegate(this, "command")
    
    private val _workingDirectory: StoredProperty<String?> = string("")
        .provideDelegate(this, "workingDirectory")

    var command: String
        get() = _command.getValue(this) ?: ""
        set(value) = _command.setValue(this, value)

    var workingDirectory: String
        get() = _workingDirectory.getValue(this) ?: ""
        set(value) = _workingDirectory.setValue(this, value)
}