package com.github.xepozz.call.base.api

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Feature generator that finds matches and can execute them.
 */
interface FeatureGenerator {
    val id: String
    val icon: Icon
    val tooltipPrefix: String

    fun isEnabled(project: Project): Boolean = true

    /**
     * Produce zero or more matches for a given block.
     */
    fun match(block: ExtractedBlock, project: Project): List<FeatureMatch>

    /**
     * Execute the given match and stream output to the provided wrapper.
     */
    fun execute(match: FeatureMatch, wrapper: Wrapper, project: Project, onProcessCreated: (ProcessHandler?) -> Unit = {})

    companion object {
        val EP_NAME: ExtensionPointName<FeatureGenerator> =
            ExtensionPointName.Companion.create("com.github.xepozz.call.feature")
    }
}