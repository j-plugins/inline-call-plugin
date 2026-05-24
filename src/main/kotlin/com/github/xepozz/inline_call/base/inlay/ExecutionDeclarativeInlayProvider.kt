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
import com.intellij.openapi.vfs.VirtualFile
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
    private val virtualFile: VirtualFile? = file.virtualFile

    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val matchesByElement = computeMatches(file).ifEmpty { return }

        // Pre-compute per-(featureId, valueHash) occurrence indices so the
        // payload carries a stable identity that survives line shifts.
        val allOrdered = matchesByElement.values.flatten()
            .sortedBy { it.originalRange.startOffset }
        val counters = HashMap<Pair<String, String>, Int>()
        val occurrenceIndex = java.util.IdentityHashMap<FeatureMatch, Int>()
        for (m in allOrdered) {
            val groupKey = m.featureId to SessionStorage.hashValue(m.value)
            val next = counters[groupKey] ?: 0
            occurrenceIndex[m] = next
            counters[groupKey] = next + 1
        }

        // Stale-key sweep: any session for this file whose key no longer
        // corresponds to a current match is evicted (process terminated,
        // panel disposed). Done once per inlay pass.
        virtualFile?.let { vf ->
            val validKeys = HashSet<String>(occurrenceIndex.size)
            occurrenceIndex.forEach { (m, occ) ->
                validKeys += SessionStorage.makeKey(vf, m.featureId, SessionStorage.hashValue(m.value), occ)
            }
            val evicted = sessionStorage.evictStale(vf.url, validKeys)
            evicted.forEach { session ->
                try { session.processHandler?.destroyProcess() } catch (_: Throwable) {}
                session.processHandler = null
            }
        }

        for ((_, matchList) in matchesByElement) {
            for (m in matchList) {
                val offset = m.originalRange.startOffset
                if (offset < 0 || offset > editor.document.textLength) continue
                emitButtonsForMatch(sink, offset, m, occurrenceIndex[m] ?: 0)
            }
        }
    }

    private fun emitButtonsForMatch(
        sink: InlayTreeSink,
        offset: Int,
        match: FeatureMatch,
        occurrenceIndex: Int,
    ) {
        val line = editor.document.getLineNumber(offset)
        val valueHash = SessionStorage.hashValue(match.value)
        val key = makeKey(editor, virtualFile, match.featureId, match.value, occurrenceIndex, line)
        val session = sessionStorage.getSession(key)
        val state = session?.state ?: ExecutionState.IDLE

        val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == match.featureId }
        val tooltipBase = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value
        val payload = ExecutionInlayPayload(match.featureId, valueHash, occurrenceIndex, line)

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
