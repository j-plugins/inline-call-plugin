package com.github.xepozz.inline_call.base.api

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.extensions.ProjectExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Feature generator that finds matches and can execute them.
 */
interface FeatureGenerator<TWrapper : Wrapper> {
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
     *
     * Returns the [ProcessHandler] driving the execution, or `null` when the
     * feature does not have process semantics or failed to start. Implementations
     * must have already called [ProcessHandler.startNotify] before returning.
     *
     * Callers should launch this from a [kotlinx.coroutines.CoroutineScope]
     * bound to the project (`SessionStorage.cs`) so cancellation propagates
     * to in-flight IO.
     */
    suspend fun execute(
        match: FeatureMatch,
        wrapper: TWrapper,
        project: Project,
    ): ProcessHandler?

    fun createWrapper(): TWrapper

    companion object {
        val EP_NAME: ProjectExtensionPointName<FeatureGenerator<*>> = ProjectExtensionPointName("com.github.xepozz.inline_call.feature")

        fun getApplicable(project: Project): List<FeatureGenerator<*>> =
            EP_NAME.getExtensions(project).filter { it.isEnabled(project) }

    }
}
