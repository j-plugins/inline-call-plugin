package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.api.LanguageTextExtractor
import com.github.xepozz.inline_call.base.extractors.AdapterLanguageExtractor
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFontSize
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.HintMarginPadding
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Declarative inlay provider that renders Run / Stop / Rerun / Delete /
 * Collapse text buttons next to each feature match.
 *
 * Click handling is dispatched through application-level
 * [com.intellij.codeInsight.hints.declarative.InlayActionHandler]s — see
 * [ExecutionInlayActionHandlers.kt]. State lives in [SessionStorage] (project
 * service) and is refreshed by [ExecutionController.refreshInlays] after
 * every mutation.
 *
 * Buttons are text-only: the public [com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder]
 * has no icon(...) method, so we render labels like "Run", "Stop", "Delete",
 * "Collapse" / "Expand", "Rerun" with `hasBackground = true` so the platform
 * draws the standard inlay pill around them.
 */
class ExecutionDeclarativeInlayProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector =
        ExecutionDeclarativeCollector(file, editor)
}

private class ExecutionDeclarativeCollector(
    private val file: PsiFile,
    private val editor: Editor,
) : OwnBypassCollector {

    private val project: Project = file.project
    private val sessionStorage = SessionStorage.getInstance(project)

    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val matches = computeMatches(file).ifEmpty { return }

        for ((_, matchList) in matches) {
            for (m in matchList) {
                val offset = m.originalRange.startOffset
                if (offset < 0 || offset > editor.document.textLength) continue
                emitButtonsForMatch(sink, offset, m)
            }
        }
    }

    private fun emitButtonsForMatch(sink: InlayTreeSink, offset: Int, match: FeatureMatch) {
        val line = editor.document.getLineNumber(offset)
        val key = makeKey(editor, match.featureId, line)
        val session = sessionStorage.getSession(key)
        val state = session?.state ?: ExecutionState.IDLE

        val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == match.featureId }
        val tooltipBase = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value
        val payload = ExecutionInlayPayload(match.featureId, line)

        // Collapse / Expand button appears whenever a container is mounted.
        if (session?.container != null) {
            val label = if (session.collapsed) "Expand" else "Collapse"
            addButton(sink, offset, label, label, payload, InlayActionHandlerIds.TOGGLE_COLLAPSE)
        }

        when (state) {
            ExecutionState.IDLE -> {
                addButton(sink, offset, "Run", tooltipBase, payload, InlayActionHandlerIds.RUN)
            }

            ExecutionState.RUNNING -> {
                addButton(sink, offset, "Stop", "Stop: ${match.value}", payload, InlayActionHandlerIds.STOP)
                addButton(sink, offset, "Delete", "Delete: ${match.value}", payload, InlayActionHandlerIds.DELETE)
            }

            ExecutionState.FINISHED -> {
                addButton(sink, offset, "Rerun", "Rerun: ${match.value}", payload, InlayActionHandlerIds.RUN)
                addButton(sink, offset, "Delete", "Delete: ${match.value}", payload, InlayActionHandlerIds.DELETE)
            }
        }
    }

    private fun addButton(
        sink: InlayTreeSink,
        offset: Int,
        label: String,
        tooltip: String,
        payload: ExecutionInlayPayload,
        handlerId: String,
    ) {
        sink.addPresentation(
            position = InlineInlayPosition(offset, false),
            payloads = emptyList(),
            tooltip = tooltip,
            hintFormat = HintFormat(
                HintColorKind.Default,
                HintFontSize.AsInEditor,
                HintMarginPadding.MarginAndSmallerPadding,
            ),
        ) {
            text(label, InlayActionData(payload.toActionPayload(), handlerId))
        }
    }

    private fun computeMatches(file: PsiFile): Map<PsiElement, List<FeatureMatch>> {
        val allExtractors = LanguageTextExtractor.getApplicable(file).ifEmpty { return emptyMap() }
        val languageSpecificExtractors = allExtractors.filter { it !is AdapterLanguageExtractor }
        val extractors = languageSpecificExtractors.ifEmpty { allExtractors }

        val blocks = extractors.flatMap { it.extract(file) }.ifEmpty { return emptyMap() }
        val featureGenerators = FeatureGenerator.getApplicable(project).ifEmpty { return emptyMap() }

        val matches = mutableMapOf<PsiElement, MutableList<FeatureMatch>>()
        for (block in blocks) {
            for (featureGenerator in featureGenerators) {
                val featureMatches = featureGenerator.match(block, project).ifEmpty { continue }
                matches.computeIfAbsent(block.element) { mutableListOf() }.addAll(featureMatches)
            }
        }
        matches.values.forEach { list -> list.sortBy { it.originalRange.startOffset } }
        return matches
    }
}
