package com.github.xepozz.inline_call.base

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class SessionStorageCoroutineTest : BasePlatformTestCase() {

    fun `test session storage cs runs a launched coroutine`() {
        val storage = SessionStorage.getInstance(project)
        val ran = AtomicBoolean(false)
        storage.cs.launch {
            ran.set(true)
        }
        pumpUntil { ran.get() }
        assertTrue("launch body never ran on sessionStorage.cs", ran.get())
    }

    fun `test session storage cs launch(EDT) lands on EDT`() {
        val storage = SessionStorage.getInstance(project)
        val onEdt = AtomicBoolean(false)
        val ran = AtomicBoolean(false)
        storage.cs.launch(Dispatchers.EDT) {
            ran.set(true)
            onEdt.set(com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
        }
        pumpUntil { ran.get() }
        assertTrue("body never ran", ran.get())
        assertTrue("Dispatchers.EDT didn't actually land on EDT", onEdt.get())
    }

    /**
     * Regression guard: documents a platform/test-framework quirk that
     * forced [com.github.xepozz.inline_call.base.inlay.ExecutionController]
     * to avoid `withContext(Dispatchers.EDT)` from a coroutine launched
     * on `Dispatchers.Default` (the service scope's default dispatcher).
     *
     * In `BasePlatformTestCase`, that switch's inner body never runs even
     * with aggressive event-queue pumping. The controller therefore
     * does Phase 1 synchronously on the caller's EDT thread and uses
     * `ApplicationManager.invokeLater` for marshalling back to EDT from
     * process-handler callbacks. If this test ever starts passing — i.e.
     * the platform fixes the dispatch — the controller can be simplified
     * to use suspending Phase 1 again.
     */
    fun `test withContext EDT from Default-launched body does NOT run in tests`() {
        val storage = SessionStorage.getInstance(project)
        val edtBodyEntered = AtomicBoolean(false)
        storage.cs.launch {
            withContext(Dispatchers.EDT) {
                edtBodyEntered.set(true)
            }
        }
        // Pump for a generous timeout; we expect the body NOT to fire.
        repeat(20) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            try { Thread.sleep(20) } catch (_: InterruptedException) { return@repeat }
        }
        assertFalse(
            "withContext(EDT) from Default-launched coroutine fired in tests — " +
                "ExecutionController can drop its synchronous Phase 1 workaround.",
            edtBodyEntered.get(),
        )
    }

    fun `test delay from launched EDT body resumes`() {
        val storage = SessionStorage.getInstance(project)
        val resumed = AtomicBoolean(false)
        storage.cs.launch(Dispatchers.EDT) {
            kotlinx.coroutines.delay(20)
            resumed.set(true)
        }
        pumpUntil(timeoutMs = 5_000) { resumed.get() }
        assertTrue("delay() never resumed on EDT-launched coroutine", resumed.get())
    }

    /**
     * Documents that the `withContext` quirk in tests is not specific to
     * the EDT dispatcher — `withContext(Dispatchers.IO)` from a
     * Default-launched coroutine doesn't reliably complete in
     * `BasePlatformTestCase` either. This is why
     * [com.github.xepozz.inline_call.feature.shell.ShellFeatureAdapter]
     * no longer wraps `OSProcessHandler(commandLine)` in
     * `withContext(Dispatchers.IO)`; that switch never resumed in tests.
     */
    fun `test withContext IO from Default-launched body does NOT complete in tests`() {
        val storage = SessionStorage.getInstance(project)
        val ioBodyRan = AtomicBoolean(false)
        val resumedAfter = AtomicBoolean(false)
        storage.cs.launch {
            withContext(Dispatchers.IO) {
                ioBodyRan.set(true)
            }
            resumedAfter.set(true)
        }
        // Pump for a generous timeout; we expect the resume NOT to fire.
        repeat(20) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            try { Thread.sleep(20) } catch (_: InterruptedException) { return@repeat }
        }
        assertFalse(
            "withContext(IO) from Default-launched coroutine completed in tests — " +
                "ShellFeatureAdapter can re-introduce its IO dispatcher wrap.",
            resumedAfter.get(),
        )
    }

    private fun pumpUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!cond()) throw AssertionError("Timed out")
    }
}
