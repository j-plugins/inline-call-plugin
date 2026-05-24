package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.LanguageTextExtractor
import com.github.xepozz.inline_call.base.extractors.AdapterLanguageExtractor
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Looks up the feature + match referenced by an [ExecutionInlayPayload] and
 * launches it through [ExecutionController].
 *
 * We do **not** persist the original [com.github.xepozz.inline_call.base.api.FeatureMatch]
 * — we recompute it on click. Match objects hold a `TextRange` that is invalidated
 * as soon as the document changes, so persisting them is fragile. The click
 * path resolves a fresh match by `(featureId, valueHash, occurrenceIndex)`.
 */
class RunInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val resolved = resolveMatch(editor, parsed) ?: return

        val virtualFile = FileLookup.virtualFileFor(editor)
        val key = makeKey(editor, virtualFile, parsed.featureId, resolved.match.value, parsed.occurrenceIndex, parsed.line)
        ExecutionController.getInstance(project).run(
            editor = editor,
            feature = resolved.feature,
            match = resolved.match,
            key = key,
            lineEndOffset = resolved.lineEndOffset,
            virtualFile = virtualFile,
            occurrenceIndex = parsed.occurrenceIndex,
            anchorElement = resolved.anchorElement,
        )
    }
}

class StopInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = keyFromPayload(editor, parsed) ?: return
        ExecutionController.getInstance(project).stop(editor, key)
    }
}

class DeleteInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = keyFromPayload(editor, parsed) ?: return
        ExecutionController.getInstance(project).delete(editor, key)
    }
}

class ToggleCollapseInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = keyFromPayload(editor, parsed) ?: return
        ExecutionController.getInstance(project).toggleCollapse(editor, key)
    }
}

private fun keyFromPayload(editor: Editor, payload: ExecutionInlayPayload): String? {
    val vf = FileLookup.virtualFileFor(editor)
    // For Stop/Delete/Toggle we don't need the actual `value`: the hash already
    // came inside the payload from the Run-time emission, so we can reconstruct
    // the key without recomputing match.value.
    return if (vf != null) {
        SessionStorage.makeKey(vf, payload.featureId, payload.valueHash, payload.occurrenceIndex)
    } else {
        "ed=${editor.hashCode()}::${payload.featureId}::${payload.valueHash}#L${payload.line}"
    }
}

private data class ResolvedMatch(
    val feature: FeatureGenerator<*>,
    val match: com.github.xepozz.inline_call.base.api.FeatureMatch,
    val lineEndOffset: Int,
    val anchorElement: PsiElement?,
)

private fun resolveMatch(editor: Editor, payload: ExecutionInlayPayload): ResolvedMatch? {
    val project = editor.project ?: return null
    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null

    val allExtractors = LanguageTextExtractor.getApplicable(psiFile).ifEmpty { return null }
    val languageSpecificExtractors = allExtractors.filter { it !is AdapterLanguageExtractor }
    val extractors = languageSpecificExtractors.ifEmpty { allExtractors }

    val blocks = extractors.flatMap { it.extract(psiFile) }.ifEmpty { return null }
    val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == payload.featureId } ?: return null

    // Recompute per-(featureId, valueHash) occurrence index, sorted by offset,
    // so we can match the payload's occurrenceIndex against a fresh extraction.
    val candidates = mutableListOf<Pair<com.github.xepozz.inline_call.base.api.FeatureMatch, PsiElement>>()
    for (block in blocks) {
        val matches = feature.match(block, project).ifEmpty { continue }
        for (m in matches) {
            candidates += m to block.element
        }
    }
    val byHash = candidates
        .filter { (m, _) -> SessionStorage.hashValue(m.value) == payload.valueHash }
        .sortedBy { (m, _) -> m.originalRange.startOffset }

    val picked = byHash.getOrNull(payload.occurrenceIndex) ?: return null
    val (match, anchor) = picked
    val start = match.originalRange.startOffset
    if (start < 0 || start > editor.document.textLength) return null
    val line = editor.document.getLineNumber(start)
    val lineEndOffset = editor.document.getLineEndOffset(line)
    return ResolvedMatch(feature, match, lineEndOffset, anchor)
}
