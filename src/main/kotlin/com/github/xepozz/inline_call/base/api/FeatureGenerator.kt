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
     * Implementations must start their own background work (process, async
     * IO, etc.) and deliver the [ProcessHandler] (or `null` on failure)
     * via [onProcessCreated]. The callback is invoked on the same thread
     * that started the work, OR off-thread if the implementation defers
     * the handler creation. UI code is responsible for marshalling back
     * to EDT in the callback when needed.
     *
     * This signature is intentionally NOT `suspend`. An earlier attempt
     * to wrap the call chain in coroutines deadlocked in production
     * click paths because `withContext(Dispatchers.EDT/IO)` from a
     * coroutine launched on the service-scope's default dispatcher
     * never resumed. The callback shape mirrors the platform's own
     * `ProcessHandler` / `Task.Backgroundable` paradigm and does not
     * require any dispatcher juggling.
     */
    fun execute(
        match: FeatureMatch,
        wrapper: TWrapper,
        project: Project,
        onProcessCreated: (ProcessHandler?) -> Unit = {},
    )

    fun createWrapper(): TWrapper

    companion object {
        val EP_NAME: ProjectExtensionPointName<FeatureGenerator<*>> = ProjectExtensionPointName("com.github.xepozz.inline_call.feature")

        fun getApplicable(project: Project): List<FeatureGenerator<*>> =
            EP_NAME.getExtensions(project).filter { it.isEnabled(project) }
    }
}
