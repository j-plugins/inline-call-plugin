package com.github.xepozz.call.implementation.features

import com.github.xepozz.call.handlers.HttpExecutionHandler
import com.github.xepozz.call.implementation.api.ExtractedBlock
import com.github.xepozz.call.implementation.api.FeatureGenerator
import com.github.xepozz.call.implementation.api.FeatureMatch
import com.github.xepozz.call.implementation.api.Wrapper
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * Feature adapter that delegates matching and execution to existing HttpExecutionHandler.
 */
class HttpFeatureAdapter : FeatureGenerator {
    private val handler = HttpExecutionHandler()

    override val id: String = "http"
    override val icon = handler.icon
    override val tooltipPrefix: String = handler.tooltipPrefix

    override fun match(block: ExtractedBlock, project: Project): List<FeatureMatch> {
        val matches = handler.findMatches(block.normalizedText)
        val base = block.originalRange.startOffset
        return matches.map { m ->
            val startOriginal = base + block.mapping.toOriginal(m.offset)
            val endOriginal = startOriginal + m.value.length
            FeatureMatch(
                featureId = id,
                block = block,
                value = m.value,
                normalizedRange = TextRange(m.offset, m.offset + m.value.length),
                originalRange = TextRange(startOriginal, endOriginal),
            )
        }
    }

    override fun execute(
        match: FeatureMatch,
        wrapper: Wrapper,
        project: Project,
        onProcessCreated: (ProcessHandler?) -> Unit
    ) {
        handler.execute(match.value, wrapper.console, project, onProcessCreated)
    }
}
