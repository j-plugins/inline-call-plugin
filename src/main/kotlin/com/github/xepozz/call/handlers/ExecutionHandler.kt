package com.github.xepozz.call.handlers

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.util.regex.Pattern
import javax.swing.Icon

interface ExecutionHandler {
    val pattern: Pattern
    val icon: Icon
    val tooltipPrefix: String

    fun findMatches(text: String): List<MatchResult> {
        val matches = mutableListOf<MatchResult>()
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            matches.add(MatchResult(matcher.start(), matcher.group(1).trim()))
        }
        return matches
    }

    fun execute(value: String, console: ConsoleView, project: Project, onProcessCreated: (ProcessHandler?) -> Unit = {})
}