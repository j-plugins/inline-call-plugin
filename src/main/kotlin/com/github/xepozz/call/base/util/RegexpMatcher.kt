package com.github.xepozz.call.base.util

import com.github.xepozz.call.base.handlers.MatchResult
import java.util.regex.Pattern

class RegexpMatcher(val pattern: Pattern) {
    fun findMatches(text: String): List<MatchResult> {
        val matches = mutableListOf<MatchResult>()
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            matches.add(MatchResult(matcher.start(), matcher.group(1).trim()))
        }
        return matches
    }
}