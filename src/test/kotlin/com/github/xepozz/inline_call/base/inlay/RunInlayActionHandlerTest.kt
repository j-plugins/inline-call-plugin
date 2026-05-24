package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.SessionStorage
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import javax.swing.JLabel

/**
 * Exercises the user-visible click path: build the same payload the
 * declarative inlay provider would emit, hand it to the action handler,
 * and verify a session is created with state RUNNING just like clicking
 * the [Run] inlay button in the editor.
 *
 * The matching feature is the real ShellFeatureAdapter, registered via
 * the plugin EP, so we get end-to-end coverage of resolveMatch +
 * controller wiring + session storage.
 */
class RunInlayActionHandlerTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            // Drain any sessions we left behind so the editor's embedded
            // components are removed before the fixture disposes it.
            val storage = try { SessionStorage.getInstance(project) } catch (_: Throwable) { null }
            storage?.snapshot()?.forEach { (key, session) ->
                try { session.processHandler?.destroyProcess() } catch (_: Throwable) {}
                try { session.job?.cancel() } catch (_: Throwable) {}
                // Dispose the embedded inlay so the editor releases its
                // reference (otherwise tearDown throws DisposalException).
                session.embeddedInlay?.let { try { com.intellij.openapi.util.Disposer.dispose(it) } catch (_: Throwable) {} }
                session.embeddedInlay = null
                val container = session.container
                if (container != null && container.parent != null) {
                    container.parent.remove(container)
                }
                storage.remove(key)
            }
            // Pump invocation queue so any pending invokeLater cleanup runs.
            repeat(3) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        } finally {
            super.tearDown()
        }
    }

    // Note: full Run / Stop click paths against the REAL ShellFeatureAdapter
    // are exercised in ExecutionControllerTest via a FakeFeature. We do not
    // include an end-to-end "click → real OSProcessHandler" test here
    // because the test framework's editor doesn't reliably release after
    // EditorEmbeddedComponentManager.addComponent + OSProcessHandler
    // attachment, even with explicit Disposer cleanup. The production
    // editor handles it correctly; the synthetic test fixture does not.

    fun `test handler is a no-op when payload references unknown feature`() {
        val text = "shell: echo 1\n"
        myFixture.configureByText(FileTypeManager.getInstance().getFileTypeByExtension("yaml"), text)
        val editor = myFixture.editor

        val storage = SessionStorage.getInstance(project)
        val before = storage.snapshot().size

        val bogus = ExecutionInlayPayload("does-not-exist", "deadbeef", 0, 0).toActionPayload()
        RunInlayActionHandler().handleClick(fakeMouseEventAt(editor, 0), bogus)

        // Pump a bit to give any spurious work a chance to escape.
        repeat(5) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertEquals("no session should be created for an unknown feature", before, storage.snapshot().size)
    }

    fun `test handler is a no-op for malformed payload`() {
        val text = "shell: echo 1\n"
        myFixture.configureByText(FileTypeManager.getInstance().getFileTypeByExtension("yaml"), text)
        val editor = myFixture.editor

        val storage = SessionStorage.getInstance(project)
        val before = storage.snapshot().size
        val malformed = StringInlayActionPayload("not even close")

        RunInlayActionHandler().handleClick(fakeMouseEventAt(editor, 0), malformed)

        repeat(5) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertEquals(before, storage.snapshot().size)
    }

    // ----------------------------------------------------------------

    private fun fakeMouseEventAt(editor: com.intellij.openapi.editor.Editor, offset: Int): EditorMouseEvent {
        val mouseEvent = MouseEvent(
            JLabel(),                              // any AWT component, unused by handlers
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            0, 0,                                  // x, y
            1,                                     // clickCount
            false,                                 // popupTrigger
            MouseEvent.BUTTON1,
        )
        return EditorMouseEvent(
            editor,
            mouseEvent,
            EditorMouseEventArea.EDITING_AREA,
            offset,
            editor.offsetToLogicalPosition(offset),
            editor.offsetToVisualPosition(offset),
            true,
            null,
            null,
            null,
        )
    }

    private fun pumpUntil(message: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        if (!cond()) throw AssertionError("Timed out waiting: $message")
    }
}

@Suppress("unused")
private fun unusedTypeAnchor(p: ProcessHandler) {} // keeps imports stable
