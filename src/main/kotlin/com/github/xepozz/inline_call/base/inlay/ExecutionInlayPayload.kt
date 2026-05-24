package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile

/**
 * Compose the SessionStorage key for an action click. Prefers the stable
 * composite key (see [SessionStorage.makeKey]) when the editor's document
 * has a backing [VirtualFile]; falls back to a synthetic editor-hash key
 * for dummy/light files (testing edge cases) so behaviour matches the
 * pre-plan-05 baseline instead of crashing.
 */
fun makeKey(
    editor: Editor,
    file: VirtualFile?,
    featureId: String,
    value: String,
    occurrenceIndex: Int,
    fallbackLine: Int,
): String {
    val hash = SessionStorage.hashValue(value)
    return if (file != null) {
        SessionStorage.makeKey(file, featureId, hash, occurrenceIndex)
    } else {
        "ed=${editor.hashCode()}::${featureId}::${hash}#L${fallbackLine}"
    }
}

/**
 * Payload model carried by the declarative inlay click. Encoded as
 * `featureId|valueHash|occurrenceIndex|line` because [StringInlayActionPayload]
 * is the only payload type we want to keep small (no JSON runtime dep, no PSI pointers).
 *
 * The action (RUN / STOP / DELETE / TOGGLE_COLLAPSE) is **not** encoded into
 * the payload; it is carried by the [com.intellij.codeInsight.hints.declarative.InlayActionData.handlerId]
 * — there is a dedicated [com.intellij.codeInsight.hints.declarative.InlayActionHandler]
 * registered per action.
 */
internal data class ExecutionInlayPayload(
    val featureId: String,
    val valueHash: String,
    val occurrenceIndex: Int,
    val line: Int,
) {
    fun encode(): String = "$featureId|$valueHash|$occurrenceIndex|$line"

    fun toActionPayload(): InlayActionPayload = StringInlayActionPayload(encode())

    companion object {
        private const val SEPARATOR = "|"

        fun decode(payload: InlayActionPayload): ExecutionInlayPayload? {
            val text = (payload as? StringInlayActionPayload)?.text ?: return null
            val parts = text.split(SEPARATOR)
            if (parts.size != 4) return null
            val featureId = parts[0].ifEmpty { return null }
            val valueHash = parts[1]
            val occ = parts[2].toIntOrNull() ?: return null
            val line = parts[3].toIntOrNull() ?: return null
            return ExecutionInlayPayload(featureId, valueHash, occ, line)
        }
    }
}

internal object InlayActionHandlerIds {
    const val RUN = "inline_call.execution.run"
    const val STOP = "inline_call.execution.stop"
    const val DELETE = "inline_call.execution.delete"
    const val TOGGLE_COLLAPSE = "inline_call.execution.toggle_collapse"
}
