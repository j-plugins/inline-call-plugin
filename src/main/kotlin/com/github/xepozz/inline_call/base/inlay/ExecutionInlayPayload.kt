package com.github.xepozz.inline_call.base.inlay

import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor

/**
 * Session key shared by the collector (when it writes) and the action handlers
 * (when they read). Uses `editor.hashCode()` for back-compat with the legacy
 * provider; this is known to be unstable across IDE restarts, but fixing it is
 * tracked separately by plan 05 (session-storage-key).
 */
fun makeKey(editor: Editor, featureId: String, line: Int): String =
    "${editor.hashCode()}_${featureId}_$line"

/**
 * Payload model carried by the declarative inlay click. Encoded as
 * `featureId|line` because [StringInlayActionPayload] is the only payload
 * type we want to keep small (no Json runtime dep, no PSI pointers).
 *
 * The action (RUN / STOP / DELETE / TOGGLE_COLLAPSE) is **not** encoded into
 * the payload; it is carried by the [com.intellij.codeInsight.hints.declarative.InlayActionData.handlerId]
 * — there is a dedicated [com.intellij.codeInsight.hints.declarative.InlayActionHandler]
 * registered per action.
 */
internal data class ExecutionInlayPayload(
    val featureId: String,
    val line: Int,
) {
    fun encode(): String = "$featureId|$line"

    fun toActionPayload(): InlayActionPayload = StringInlayActionPayload(encode())

    companion object {
        private const val SEPARATOR = "|"

        fun decode(payload: InlayActionPayload): ExecutionInlayPayload? {
            val text = (payload as? StringInlayActionPayload)?.text ?: return null
            val sep = text.lastIndexOf(SEPARATOR)
            if (sep <= 0 || sep == text.length - 1) return null
            val featureId = text.substring(0, sep)
            val line = text.substring(sep + 1).toIntOrNull() ?: return null
            return ExecutionInlayPayload(featureId, line)
        }
    }
}

internal object InlayActionHandlerIds {
    const val RUN = "inline_call.execution.run"
    const val STOP = "inline_call.execution.stop"
    const val DELETE = "inline_call.execution.delete"
    const val TOGGLE_COLLAPSE = "inline_call.execution.toggle_collapse"
}
