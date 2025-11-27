package com.github.xepozz.call.base.handlers

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
}