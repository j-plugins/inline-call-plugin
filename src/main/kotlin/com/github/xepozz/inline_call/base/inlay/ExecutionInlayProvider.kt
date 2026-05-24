package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.api.LanguageTextExtractor
import com.github.xepozz.inline_call.base.api.Wrapper
import com.github.xepozz.inline_call.base.extractors.AdapterLanguageExtractor
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.inlay.ui.createResultContainer
import com.github.xepozz.inline_call.base.inlay.ui.embedContainerIntoEditor
import com.github.xepozz.inline_call.base.makeSessionKey
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Cursor
import java.util.IdentityHashMap
import javax.swing.Icon
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class ExecutionInlayProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("inline_call.implementation.inlay")
    override val name: String = "Call (Unified)"
    override val previewText: String = "// shell: echo hello\n// https://api.example.com"

    override fun createSettings(): NoSettings = NoSettings()
    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = panel { }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ) = object : FactoryInlayHintsCollector(editor) {
        val sessionStorage = SessionStorage.getInstance(file.project)

        // Pre-compute matches for the whole file once
        private val matchesByElement: Map<PsiElement, List<FeatureMatch>> = computeMatches(file)

        // Pre-computed occurrence index per FeatureMatch (identity-keyed).
        private val occurrenceIndex: Map<FeatureMatch, Int> = computeOccurrenceIndex(matchesByElement)

        private val virtualFile: VirtualFile? = file.virtualFile
        private val virtualFileUrl: String? = virtualFile?.url

        init {
            virtualFileUrl?.let { url ->
                val validKeys = buildValidKeysFor(url)
                val evicted = sessionStorage.evictStale(url, validKeys)
                evicted.forEach { session ->
                    try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
                    session.processHandler = null
                    val container = session.container
                    if (container != null) {
                        sessionStorage.cs.launch(Dispatchers.EDT) {
                            container.parent?.remove(container)
                        }
                    }
                }
            }
        }

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val matches = matchesByElement[element] ?: return true
            if (matches.isEmpty()) return true

            val project = file.project

            matches.forEach { m ->
                val offset = m.originalRange.startOffset
                val pres = buildActionsPresentation(editor, project, m, element)
                sink.addInlineElement(offset, false, pres, false)
            }
            return true
        }

        private fun computeMatches(file: PsiFile): Map<PsiElement, List<FeatureMatch>> {
            val project = file.project
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

            matches.values.forEach { list ->
                list.sortBy { it.originalRange.startOffset }
            }

            return matches
        }

        private fun computeOccurrenceIndex(
            matchesByElement: Map<PsiElement, List<FeatureMatch>>
        ): Map<FeatureMatch, Int> {
            val all = matchesByElement.values.flatten()
                .sortedBy { it.originalRange.startOffset }
            val counters = HashMap<Pair<String, String>, Int>()
            val result = IdentityHashMap<FeatureMatch, Int>()
            for (m in all) {
                val groupKey = m.featureId to SessionStorage.hashValue(m.value)
                val next = counters[groupKey] ?: 0
                result[m] = next
                counters[groupKey] = next + 1
            }
            return result
        }

        private fun buildValidKeysFor(url: String): Set<String> {
            val vf = virtualFile ?: return emptySet()
            if (vf.url != url) return emptySet()
            val keys = HashSet<String>(occurrenceIndex.size)
            occurrenceIndex.forEach { (m, occ) ->
                keys += makeSessionKey(vf, m, occ)
            }
            return keys
        }

        private fun buildActionsPresentation(
            editor: Editor,
            project: Project,
            match: FeatureMatch,
            element: PsiElement,
        ): InlayPresentation {
            val feature = FeatureGenerator.getApplicable(project).firstOrNull { it.id == match.featureId }
            val icon: Icon? = feature?.icon
            val tooltip = feature?.let { "${it.tooltipPrefix}: ${match.value}" } ?: match.value

            val start = match.originalRange.startOffset
            val line = editor.document.getLineNumber(start)
            val lineEndOffset = editor.document.getLineEndOffset(line)
            val vf = virtualFile
            val occ = occurrenceIndex[match] ?: 0
            val key = if (vf != null) makeSessionKey(vf, match, occ) else legacyFallbackKey(editor, match, line)
            val session = sessionStorage.getSession(key)

            val parts = mutableListOf<InlayPresentation>()

            if (session?.container != null) {
                val collapseIcon = if (session.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
                val collapseTooltip = if (session.collapsed) "Expand" else "Collapse"
                parts += clickableIcon(collapseIcon, collapseTooltip) {
                    toggleCollapse(session)
                    refreshInlays(editor)
                }
            }

            when (session?.state ?: ExecutionState.IDLE) {
                ExecutionState.RUNNING -> {
                    parts += clickableIcon(AllIcons.Actions.Suspend, "Stop") {
                        stop(key)
                        refreshInlays(editor)
                    }
                    parts += clickableIcon(AllIcons.General.Remove, "Delete") {
                        delete(key)
                        refreshInlays(editor)
                    }
                }

                ExecutionState.IDLE -> {
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = icon?.let { factory.seq(factory.icon(it), runText) } ?: runText
                    var runPres: InlayPresentation = factory.withTooltip(tooltip, withIcon)
                    runPres = factory.withCursorOnHover(runPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    runPres = factory.onClick(runPres, MouseButton.Left) { _, _ ->
                        val featureGenerator = feature ?: return@onClick
                        run(editor, project, featureGenerator, match, key, lineEndOffset, element)
                    }
                    parts += runPres
                }

                ExecutionState.FINISHED -> {
                    val rerunIcon = AllIcons.Actions.Rerun
                    val rerunTooltip = "Rerun: ${match.value}"
                    val runText = factory.inset(factory.roundWithBackground(factory.text(" Run ")), left = 2, right = 2)
                    val withIcon = factory.seq(factory.icon(rerunIcon), runText)
                    var rerunPres: InlayPresentation = factory.withTooltip(rerunTooltip, withIcon)
                    rerunPres = factory.withCursorOnHover(rerunPres, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
                    rerunPres = factory.onClick(rerunPres, MouseButton.Left) { _, _ ->
                        val feat = feature ?: return@onClick
                        run(editor, project, feat, match, key, lineEndOffset, element)
                    }
                    parts += rerunPres

                    parts += clickableIcon(AllIcons.General.Remove, "Delete") {
                        delete(key)
                        refreshInlays(editor)
                    }
                }
            }

            return if (parts.size == 1) parts.first() else factory.seq(*parts.toTypedArray())
        }

        private fun clickableIcon(icon: Icon, tooltip: String, onClick: () -> Unit): InlayPresentation {
            var p: InlayPresentation = factory.icon(icon)
            p = factory.inset(p, 2, 2, 0, 0)
            p = factory.withTooltip(tooltip, p)
            p = factory.withCursorOnHover(p, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
            p = factory.onClick(p, MouseButton.Left) { _, _ -> onClick() }
            return p
        }

        private fun <TWrapper : Wrapper> run(
            editor: Editor,
            project: Project,
            feature: FeatureGenerator<TWrapper>,
            match: FeatureMatch,
            key: String,
            lineEndOffset: Int,
            element: PsiElement,
        ) {
            val newJob = sessionStorage.cs.launch(CoroutineName("inline-call:$key")) {
                runImpl(editor, project, feature, match, key, lineEndOffset, element)
            }
            sessionStorage.replaceJob(key, newJob)?.cancel()
        }

        private suspend fun <TWrapper : Wrapper> runImpl(
            editor: Editor,
            project: Project,
            feature: FeatureGenerator<TWrapper>,
            match: FeatureMatch,
            key: String,
            lineEndOffset: Int,
            element: PsiElement,
        ) {
            val wrapper = feature.createWrapper()
            val vfUrl = virtualFileUrl ?: ""
            val occ = occurrenceIndex[match] ?: 0
            val valueHash = SessionStorage.hashValue(match.value)
            val anchor: SmartPsiElementPointer<PsiElement>? =
                if (element.isValid) SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
                else null

            // Phase 1: ensure container exists and is mounted; replace wrapper.
            val session = withContext(Dispatchers.EDT) {
                var current = sessionStorage.getSession(key)

                // Container may be null because (a) never mounted, or (b) the
                // previous editor was disposed on file close (container exists
                // but its parent is null — rebuild and re-mount).
                val needsContainer = current?.container == null || current.container?.parent == null

                if (needsContainer) {
                    val container = try {
                        val c = createResultContainer()
                        embedContainerIntoEditor(editor, c, lineEndOffset)
                        mountWrapperIntoContainer(c, wrapper)
                        c
                    } catch (_: Throwable) {
                        null
                    }
                    if (current == null) {
                        current = Session(
                            virtualFileUrl = vfUrl,
                            featureId = match.featureId,
                            valueHash = valueHash,
                            occurrenceIndex = occ,
                            anchor = anchor,
                            container = container,
                            wrapper = wrapper,
                        )
                    } else {
                        current.container = container
                        current.wrapper = wrapper
                        if (current.anchor == null) current.anchor = anchor
                    }
                    sessionStorage.putSession(key, current)
                } else {
                    val container = current!!.container!!
                    val oldWrapper = current.wrapper
                    if (oldWrapper != null) {
                        container.remove(oldWrapper.component)
                    }
                    mountWrapperIntoContainer(container, wrapper)
                    current.wrapper = wrapper
                    if (current.anchor == null) current.anchor = anchor
                }
                current.state = ExecutionState.RUNNING
                refreshInlaysOnEdt(editor)
                current
            }

            // Phase 2: execute. feature.execute manages its own dispatcher hops.
            val handler = feature.execute(match, wrapper, project)
            session.processHandler = handler

            if (handler == null) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    session.state = ExecutionState.FINISHED
                    refreshInlaysOnEdt(editor)
                }
                return
            }

            // Phase 3: bridge ProcessListener -> CompletableDeferred so the
            // coroutine awaits termination without blocking a thread.
            val done = CompletableDeferred<Unit>()
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    done.complete(Unit)
                }
            })
            try {
                done.await()
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    session.processHandler = null
                    session.state = ExecutionState.FINISHED
                    refreshInlaysOnEdt(editor)
                }
            }
        }

        private fun mountWrapperIntoContainer(container: JPanel, wrapper: Wrapper) {
            // Caller is expected to already be on the EDT.
            container.add(wrapper.component, BorderLayout.CENTER)
            container.revalidate()
            container.repaint()
        }

        private fun stop(key: String) {
            val session = sessionStorage.getSession(key) ?: return
            session.job?.cancel()
            session.processHandler?.destroyProcess()
            session.processHandler = null
            session.state = ExecutionState.FINISHED
        }

        private fun delete(key: String) {
            val session = sessionStorage.remove(key) ?: return
            try { session.processHandler?.destroyProcess() } catch (_: Throwable) { }
            session.processHandler = null
            sessionStorage.cs.launch(Dispatchers.EDT) {
                session.container?.parent?.remove(session.container)
            }
        }

        private fun toggleCollapse(session: Session) {
            session.collapsed = !session.collapsed
            val visible = !session.collapsed
            sessionStorage.cs.launch(Dispatchers.EDT) {
                session.container?.isVisible = visible
            }
        }

        private fun refreshInlays(editor: Editor) {
            val project = editor.project ?: return
            sessionStorage.cs.launch(Dispatchers.EDT) {
                InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }

        // Same as refreshInlays but assumes caller is already on the EDT.
        private fun refreshInlaysOnEdt(editor: Editor) {
            val project = editor.project ?: return
            InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}

/**
 * Fallback used when the PSI file has no backing [VirtualFile] (e.g.
 * dummy/light files in some test situations). We synthesise a key from
 * the editor identity so behaviour matches the pre-plan-05 baseline for
 * those edge cases instead of crashing.
 */
private fun legacyFallbackKey(editor: Editor, match: FeatureMatch, line: Int): String =
    "ed=${editor.hashCode()}::${match.featureId}::${SessionStorage.hashValue(match.value)}#L${line}"
