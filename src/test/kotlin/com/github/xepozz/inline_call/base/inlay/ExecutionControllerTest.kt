package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.api.ExtractedBlock
import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.api.Wrapper
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.makeSessionKey
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Tests for the click path from inlay action handler all the way to
 * [ExecutionController.run]: state transitions, key shape, session
 * lifecycle, and process handler propagation.
 *
 * Tests run on EDT, so we use [PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue]
 * to drain queued EDT events between assertions — that is how
 * `withContext(Dispatchers.EDT)` in the controller actually progresses.
 */
class ExecutionControllerTest : BasePlatformTestCase() {

    fun `test run creates session and transitions IDLE to RUNNING then FINISHED`() {
        val (controller, sessionStorage, feature, file) = setupController()
        val match = feature.synthesizeMatch("echo 1", 0)
        val editor = myFixture.editor
        val vf = file.virtualFile
        val key = makeSessionKey(vf, match, occurrenceIndex = 0)

        assertNull("no session before run", sessionStorage.getSession(key))

        controller.run(
            editor = editor,
            feature = feature,
            match = match,
            key = key,
            lineEndOffset = editor.document.getLineEndOffset(0),
            virtualFile = vf,
            occurrenceIndex = 0,
            anchorElement = null,
        )

        pumpUntil("execute was called") { feature.executeCalled.get() }

        val running = sessionStorage.getSession(key)
        assertNotNull("session must exist after run() launches coroutine", running)
        assertEquals(ExecutionState.RUNNING, running!!.state)
        assertEquals(0, running.occurrenceIndex)
        assertEquals(match.featureId, running.featureId)
        assertNotNull("processHandler must be attached", running.processHandler)

        // Drive the fake process to termination.
        feature.terminate(exitCode = 0)
        pumpUntil("finished") { sessionStorage.getSession(key)?.state == ExecutionState.FINISHED }

        val finished = sessionStorage.getSession(key)
        assertNotNull("session retained after finish", finished)
        assertEquals(ExecutionState.FINISHED, finished!!.state)
        assertNull("processHandler cleared on finish", finished.processHandler)
    }

    fun `test stop cancels job and destroys process`() {
        val (controller, sessionStorage, feature, file) = setupController()
        val match = feature.synthesizeMatch("sleep 30", 0)
        val editor = myFixture.editor
        val vf = file.virtualFile
        val key = makeSessionKey(vf, match, 0)

        controller.run(
            editor = editor, feature = feature, match = match, key = key,
            lineEndOffset = editor.document.getLineEndOffset(0),
            virtualFile = vf, occurrenceIndex = 0, anchorElement = null,
        )
        pumpUntil("execute called") { feature.executeCalled.get() }

        controller.stop(editor, key)
        pumpUntil("session FINISHED") { sessionStorage.getSession(key)?.state == ExecutionState.FINISHED }

        val s = sessionStorage.getSession(key)
        assertNotNull(s)
        assertEquals(ExecutionState.FINISHED, s!!.state)
        assertNull("processHandler cleared by stop", s.processHandler)
        assertTrue("fake process must have received destroy", feature.destroyedHandler.get())
    }

    fun `test delete removes session entirely`() {
        val (controller, sessionStorage, feature, file) = setupController()
        val match = feature.synthesizeMatch("echo bye", 0)
        val editor = myFixture.editor
        val vf = file.virtualFile
        val key = makeSessionKey(vf, match, 0)

        controller.run(
            editor = editor, feature = feature, match = match, key = key,
            lineEndOffset = editor.document.getLineEndOffset(0),
            virtualFile = vf, occurrenceIndex = 0, anchorElement = null,
        )
        pumpUntil("execute called") { feature.executeCalled.get() }
        feature.terminate(exitCode = 0)
        pumpUntil("finished") { sessionStorage.getSession(key)?.state == ExecutionState.FINISHED }

        controller.delete(editor, key)
        pumpUntil("session removed") { sessionStorage.getSession(key) == null }

        assertNull("session removed after delete", sessionStorage.getSession(key))
    }

    fun `test run twice replaces wrapper and re-execute`() {
        val (controller, sessionStorage, feature, file) = setupController()
        val match = feature.synthesizeMatch("echo 1", 0)
        val editor = myFixture.editor
        val vf = file.virtualFile
        val key = makeSessionKey(vf, match, 0)

        controller.run(
            editor = editor, feature = feature, match = match, key = key,
            lineEndOffset = editor.document.getLineEndOffset(0),
            virtualFile = vf, occurrenceIndex = 0, anchorElement = null,
        )
        pumpUntil("first execute") { feature.executeCallCount.get() == 1 }
        val firstHandler = sessionStorage.getSession(key)?.processHandler
        assertNotNull(firstHandler)

        // First run terminates so the second one can re-attach a fresh handler.
        feature.terminate(0)
        pumpUntil("first finished") { sessionStorage.getSession(key)?.state == ExecutionState.FINISHED }

        controller.run(
            editor = editor, feature = feature, match = match, key = key,
            lineEndOffset = editor.document.getLineEndOffset(0),
            virtualFile = vf, occurrenceIndex = 0, anchorElement = null,
        )
        pumpUntil("second execute") { feature.executeCallCount.get() == 2 }

        val s2 = sessionStorage.getSession(key)
        assertNotNull(s2)
        assertEquals(ExecutionState.RUNNING, s2!!.state)
        assertNotSame("second run produces a fresh handler", firstHandler, s2.processHandler)
    }

    // ---------------------------------------------------------------------

    private data class Fixture(
        val controller: ExecutionController,
        val sessionStorage: SessionStorage,
        val feature: FakeFeature,
        val file: PsiFile,
    )

    private fun setupController(): Fixture {
        val file = myFixture.configureByText(PlainTextFileType.INSTANCE, "echo 1\n")
        val sessionStorage = SessionStorage.getInstance(project)
        val controller = ExecutionController.getInstance(project)
        val feature = FakeFeature()
        return Fixture(controller, sessionStorage, feature, file)
    }

    /**
     * Drains pending EDT events and waits up to [timeoutMs] ms for [cond]
     * to become true. Required because the controller uses
     * `withContext(Dispatchers.EDT)`; on the test thread (EDT) those
     * runnables are scheduled on the event queue and need to be pumped.
     */
    private fun pumpUntil(message: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!cond()) throw AssertionError("Timed out waiting: $message")
    }
}

/**
 * Test-only [FeatureGenerator] that lets the test drive execute()/terminate()
 * deterministically through atomics without spawning real processes.
 */
internal class FakeFeature : FeatureGenerator<FakeFeature.FakeWrapper> {
    override val id: String = "fake"
    override val icon: Icon = com.intellij.icons.AllIcons.Actions.Execute
    override val tooltipPrefix: String = "Fake"

    val executeCalled = AtomicBoolean(false)
    val executeCallCount = AtomicInteger(0)
    val destroyedHandler = AtomicBoolean(false)

    @Volatile private var currentHandler: FakeProcessHandler? = null

    fun synthesizeMatch(value: String, startOffset: Int): FeatureMatch {
        val block = ExtractedBlock(
            element = com.intellij.psi.impl.source.tree.LeafPsiElement(
                com.intellij.psi.tree.IElementType("FAKE", null),
                value,
            ),
            originalRange = TextRange(startOffset, startOffset + value.length),
            text = value,
        )
        return FeatureMatch(
            featureId = id,
            block = block,
            value = value,
            normalizedRange = TextRange(0, value.length),
            originalRange = TextRange(startOffset, startOffset + value.length),
        )
    }

    fun terminate(exitCode: Int) {
        // Off-EDT to mimic real OSProcessHandler, which fires processTerminated
        // from a worker thread. Tests must observe the same dispatch path.
        val h = currentHandler ?: return
        val t = Thread { h.complete(exitCode) }
        t.start()
        t.join()
    }

    override fun match(block: ExtractedBlock, project: com.intellij.openapi.project.Project): List<FeatureMatch> = emptyList()

    override suspend fun execute(
        match: FeatureMatch,
        wrapper: FakeWrapper,
        project: com.intellij.openapi.project.Project,
    ): ProcessHandler {
        val handler = FakeProcessHandler { destroyedHandler.set(true) }
        currentHandler = handler
        handler.startNotify()
        executeCallCount.incrementAndGet()
        executeCalled.set(true)
        return handler
    }

    override fun createWrapper() = FakeWrapper()

    class FakeWrapper : Wrapper {
        override val component: JComponent = JLabel("fake")
    }

    private class FakeProcessHandler(val onDestroy: () -> Unit) : ProcessHandler() {
        @Volatile private var terminated = false
        override fun destroyProcessImpl() {
            if (!terminated) {
                terminated = true
                onDestroy()
                notifyProcessTerminated(143)
            }
        }
        override fun detachProcessImpl() {
            if (!terminated) {
                terminated = true
                notifyProcessDetached()
            }
        }
        override fun detachIsDefault(): Boolean = false
        override fun getProcessInput(): OutputStream? = null
        fun complete(exitCode: Int) {
            if (!terminated) {
                terminated = true
                notifyProcessTerminated(exitCode)
            }
        }
    }
}
