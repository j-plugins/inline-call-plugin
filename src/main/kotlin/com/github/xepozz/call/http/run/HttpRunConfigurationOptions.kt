// HttpRunConfigurationOptions.kt

package com.github.xepozz.call.http.run

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class HttpRunConfigurationOptions : RunConfigurationOptions() {
    
    private val _url: StoredProperty<String?> = string("")
        .provideDelegate(this, "url")
    
    private val _method: StoredProperty<String?> = string("GET")
        .provideDelegate(this, "method")
    
    private val _headers: StoredProperty<String?> = string("")
        .provideDelegate(this, "headers")
    
    private val _body: StoredProperty<String?> = string("")
        .provideDelegate(this, "body")
    
    private val _timeout: StoredProperty<Int> = property(30)
        .provideDelegate(this, "timeout")

    var url: String
        get() = _url.getValue(this) ?: ""
        set(value) = _url.setValue(this, value)

    var method: String
        get() = _method.getValue(this) ?: "GET"
        set(value) = _method.setValue(this, value)

    var headers: String
        get() = _headers.getValue(this) ?: ""
        set(value) = _headers.setValue(this, value)

    var body: String
        get() = _body.getValue(this) ?: ""
        set(value) = _body.setValue(this, value)

    var timeout: Int
        get() = _timeout.getValue(this)
        set(value) = _timeout.setValue(this, value)
}