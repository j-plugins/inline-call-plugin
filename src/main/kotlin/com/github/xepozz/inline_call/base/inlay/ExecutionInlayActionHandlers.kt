package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.LanguageTextExtractor
import com.github.xepozz.inline_call.base.extractors.AdapterLanguageExtractor
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.psi.PsiDocumentManager

/**
 * Looks up the feature + match referenced by an [ExecutionInlayPayload] and
 * launches it through [ExecutionController].
 *
 * We do **not** persist the original [com.github.xepozz.inline_call.base.api.FeatureMatch]
 * — we recompute it on click. Match objects hold a `TextRange` that is invalidated
 * as soon as the document changes, so persisting them is fragile. The click
 * path resolves a fresh match by `(featureId, line)`.
 */
class RunInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val resolved = resolveMatch(editor, parsed) ?: return

        val key = makeKey(editor, parsed.featureId, parsed.line)
        ExecutionController.getInstance(project).run(
            editor = editor,
            feature = resolved.feature,
            match = resolved.match,
            key = key,
            lineEndOffset = resolved.lineEndOffset,
        )
    }
}

class StopInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = makeKey(editor, parsed.featureId, parsed.line)
        ExecutionController.getInstance(project).stop(editor, key)
    }
}

class DeleteInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = makeKey(editor, parsed.featureId, parsed.line)
        ExecutionController.getInstance(project).delete(editor, key)
    }
}

class ToggleCollapseInlayActionHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val editor = e.editor
        val parsed = ExecutionInlayPayload.decode(payload) ?: return
        val project = editor.project ?: return
        val key = makeKey(editor, parsed.featureId, parsed.line)
        ExecutionController.getInstance(project).toggleCollapse(editor, key)
    }
}

private data class ResolvedMatch(
    val feature: FeatureGenerator<*>,
    val match: com.github.xepozz.inline_call.base.api.FeatureMatch,
    val lineEndOffset: Int,
)

private fun resolveMatch(editor: Editor, payload: ExecutionInlayPayload): ResolvedMatch? {
    val project = editor.project ?: return null
    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null

    val allExtractors = LanguageTextExtractor.getApplicable(psiFile).ifEmpty { return null }
    val languageSpecificExtractors = allExtractors.filter { it !is AdapterLanguageExtractor }
    val extractors = languageSpecificExtractors.ifEmpty { allExtractors }

    val blocks = extractors.flatMap { it.extract(psiFile) }.ifEmpty { return null }
    val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == payload.featureId } ?: return null

    for (block in blocks) {
        val matches = feature.match(block, project).ifEmpty { continue }
        for (m in matches) {
            val start = m.originalRange.startOffset
            if (start < 0 || start > editor.document.textLength) continue
            val line = editor.document.getLineNumber(start)
            if (line == payload.line) {
                val lineEndOffset = editor.document.getLineEndOffset(line)
                return ResolvedMatch(feature, m, lineEndOffset)
            }
        }
    }
    return null
}
