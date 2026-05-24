package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.api.Wrapper
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.coroutines.Job
import javax.swing.JPanel

/**
 * In-memory representation of a single "Run-click" outcome for a logical
 * invocation site (a `(virtualFileUrl, featureId, valueHash, occurrenceIndex)`
 * tuple — see [SessionStorage.makeKey]).
 *
 * Mutable fields are `@Volatile` because the lifecycle listeners (EDT) and
 * `ProcessHandler` callbacks (worker threads) write concurrently.
 *
 * [job] is the optional coroutine running the current execution; it is
 * cancelled when the session is removed from storage or evicted.
 */
class Session(
    val virtualFileUrl: String,
    val featureId: String,
    val valueHash: String,
    val occurrenceIndex: Int,
    @Volatile var anchor: SmartPsiElementPointer<PsiElement>?,
    @Volatile var container: JPanel?,
    @Volatile var wrapper: Wrapper?,
    @Volatile var state: ExecutionState = ExecutionState.IDLE,
    @Volatile var processHandler: ProcessHandler? = null,
    @Volatile var collapsed: Boolean = false,
    @Volatile var job: Job? = null,
    @Volatile var embeddedInlay: Disposable? = null,
) {
    /**
     * Whether the source anchor (typically a `PsiComment`) still exists and
     * is valid in the PSI tree.
     *
     * Resolves the [SmartPsiElementPointer] inside a read action because
     * [SmartPsiElementPointer.getElement] requires read access.
     *
     * A session without an anchor (`anchor == null`) is treated as alive —
     * older / freshly created sessions that have not yet been bound to a
     * PSI element should not be reaped by the lifecycle listeners.
     */
    fun isAlive(): Boolean {
        val ptr = anchor ?: return true
        return ReadAction.compute<Boolean, RuntimeException> {
            val el = ptr.element
            el != null && el.isValid
        }
    }
}
