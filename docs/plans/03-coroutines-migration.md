# 03 — Migrate invokeLater to Kotlin coroutines

## Problem

The plugin currently hops between threads with a mix of low-level primitives:

- `com.intellij.openapi.application.invokeLater { ... }` is used in 8 places to push UI
  mutations onto the EDT.
- The HTTP feature drives `HttpClient.sendAsync(...)` (a `CompletableFuture`) and then
  re-enters the EDT from a `whenComplete` callback.
- `HttpRunState` spawns a raw `kotlin.concurrent.thread { ... }` from `startNotify()` to
  perform a blocking `httpClient.send(...)`, and reaches back to the EDT through
  `invokeLater` for every console line.
- `ShellFeatureAdapter` catches exceptions and pushes the error text to the console via
  `invokeLater` — even though the surrounding code is already on the EDT.

Problems caused by this style:

1. **No cancellation surface.** Closing the project does not interrupt in-flight HTTP
   requests or pending `invokeLater` lambdas. `SessionStorage` keeps references to
   `ProcessHandler`s and `CompletableFuture`s that may outlive the project.
2. **No structured concurrency.** Errors in callbacks are swallowed (e.g. the
   `whenComplete` block in `HttpFeatureAdapter` only handles success/failure of the
   request itself; an exception inside `printResponse` propagates to a fork-join thread).
3. **EDT correctness is hand-wavy.** `mountWrapperIntoContainer` is `invokeLater`-ed,
   but every call site is already on the EDT (it is invoked from an inlay click handler
   and from `collect()`), so the wrapper is sometimes added on the *next* UI tick — the
   inlay refresh races with it.
4. **Inconsistent dispatch.** `embedContainerIntoEditor` schedules its add via
   `invokeLater`, while the surrounding `run()` already mutates `Session.state` on the
   EDT — so the container becomes visible *after* the inlay refresh has already
   reported state `RUNNING`.
5. **No background thread for blocking IO.** `httpClient.send(...)` in `HttpRunState`
   runs on a hand-rolled thread instead of `Dispatchers.IO`, so it is invisible to the
   platform's progress / cancellation infrastructure.

The IntelliJ Platform has provided a first-class coroutine integration since 2024.1
(`Dispatchers.EDT`, `Dispatchers.Default`, suspending `readAction`/`writeAction`,
service-bound `cs: CoroutineScope`, `currentThreadCoroutineScope()` for actions). The
plugin targets `pluginSinceBuild = 251` (IDEA 2025.1), so every API needed for the
migration is available unconditionally.

## All call sites

Verified with `grep -rn "invokeLater" src --include='*.kt'`:

| # | File | Line | Context |
|---|------|------|---------|
| 1 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt` | 26  | `import com.intellij.openapi.application.invokeLater` |
| 2 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt` | 237 | `mountWrapperIntoContainer` — add wrapper to result panel, revalidate, repaint |
| 3 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt` | 255 | `delete()` — remove container from parent after session is dropped |
| 4 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt` | 263 | `toggleCollapse()` — flip `container.isVisible` |
| 5 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt` | 267–269 | `refreshInlays()` — `InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()` + `DaemonCodeAnalyzer.restart()` |
| 6 | `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ui/embedContainerIntoEditor.kt` | 20 | Add component to `EditorEmbeddedComponentManager` |
| 7 | `src/main/kotlin/com/github/xepozz/inline_call/feature/http/HttpFeatureAdapter.kt` | 11  | `import` |
| 8 | `src/main/kotlin/com/github/xepozz/inline_call/feature/http/HttpFeatureAdapter.kt` | 109 | Print error from `whenComplete` |
| 9 | `src/main/kotlin/com/github/xepozz/inline_call/feature/http/HttpFeatureAdapter.kt` | 114 | Print response from `whenComplete` |
| 10 | `src/main/kotlin/com/github/xepozz/inline_call/feature/shell/ShellFeatureAdapter.kt` | 13  | `import` |
| 11 | `src/main/kotlin/com/github/xepozz/inline_call/feature/shell/ShellFeatureAdapter.kt` | 63  | `catch (e: Exception)` — print failure to start process |
| 12 | `src/main/kotlin/com/github/xepozz/inline_call/feature/http/run/HttpRunState.kt` | 158 | `printToConsole` — every console line goes through `invokeLater` |

Related (not `invokeLater`, but coupled to the migration):

- `HttpRunState.kt:51` — `kotlin.concurrent.thread { executeRequest() }` driven from
  `startNotify()`. Should become a coroutine launched on the service scope (or on a
  short-lived `RunProfileState` scope) using `Dispatchers.IO`.
- `HttpFeatureAdapter.kt:105` — `httpClient.sendAsync(...)` returning a
  `CompletableFuture`. Worth converting to `withContext(Dispatchers.IO) { httpClient.send(...) }`
  for uniformity with cancellation semantics.

## Migration patterns

### EDT switching

Replace every `invokeLater { ... }` that mutates Swing / inlays with a suspending hop
inside an existing coroutine:

```kotlin
withContext(Dispatchers.EDT) {
    container.add(wrapper.component, BorderLayout.CENTER)
    container.revalidate()
    container.repaint()
}
```

Rules of thumb (from `topics/.../coroutine_dispatchers.md`):

- `Dispatchers.EDT` is the **only** dispatcher that may touch Swing components,
  inlays, editor presentation, or `DaemonCodeAnalyzer`.
- The dispatcher captures the right modality state. We do not need `ModalityState`
  arguments when launching from a service scope: pass
  `ModalityState.defaultModalityState().asContextElement()` if a click handler runs
  inside a modal dialog.
- Never use `Dispatchers.Main` from `kotlinx-coroutines-swing` — it is not aware of
  IDE modality.

### Pooled / IO work

For CPU-bound transformations (regex matching, JSON pretty-printing,
`computeMatches`):

```kotlin
withContext(Dispatchers.Default) { ... }
```

For blocking IO (HTTP, file reads):

```kotlin
withContext(Dispatchers.IO) {
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}
```

`Dispatchers.IO` is the IntelliJ-flavored one (`com.intellij.openapi.application.IO`),
not the kotlinx one. It is unbounded and designed for blocking calls.

### Long-running per-execution scope

Each "Run" click should produce a child `Job` rooted in
`SessionStorage`'s scope so that:

- Closing the project cancels every in-flight run.
- Clicking *Stop* cancels the run cooperatively (`Job.cancelAndJoin()`).
- Clicking *Rerun* cancels the previous job before starting a new one
  (currently the previous `CompletableFuture` is left dangling on error paths).

Suggested shape:

```kotlin
session.job = sessionStorage.cs.launch(CoroutineName("inline-call:$key")) {
    try {
        feature.execute(match, wrapper, project) // suspend
    } finally {
        withContext(NonCancellable + Dispatchers.EDT) {
            session.state = ExecutionState.FINISHED
            refreshInlays(editor)
        }
    }
}
```

`FeatureGenerator.execute` should become a `suspend fun` returning a
`ProcessHandler?` (or `Unit` for fire-and-forget features), eliminating the
`onProcessCreated` callback.

### Cancellation / structured concurrency

- A coroutine launched inside `sessionStorage.cs` is cancelled automatically when
  the project is disposed (the service scope is bound to the project lifecycle).
- For the `HttpFeatureAdapter`, instead of holding a `CompletableFuture` and
  cancelling it from `ProcessHandler.destroyProcessImpl`, drive the run from a
  coroutine; `destroyProcessImpl` calls `job.cancel()`, the suspending
  `httpClient.send` is wrapped in `runInterruptible(Dispatchers.IO) { ... }` so
  cancellation maps to thread interrupt.
- Avoid `GlobalScope` and `CoroutineScope(SupervisorJob())` entirely — both detach
  from the IDE lifecycle.
- For UI mutations made during a coroutine that is being cancelled (e.g. final
  console.print on failure), wrap them in `withContext(NonCancellable + Dispatchers.EDT)`.

## Implementation steps

Each step is intended as one atomic commit. Commits build on each other; tests should
pass at every step.

### Step 1 — Add coroutine scopes to project services

- Convert `SessionStorage` from `class SessionStorage(val project: Project)` to
  `class SessionStorage(val project: Project, val cs: CoroutineScope)`. The platform
  injects the scope when the constructor declares one (since 2024.1).
- Add a `Session.job: Job?` field so we can cancel an in-flight run.
- Expose helpers: `fun cancelAll()` (called from `dispose`) and
  `fun replaceJob(key, Job): Job?` returning the previously stored job.
- No behaviour change for callers yet.

### Step 2 — Introduce a suspending UI helper module

- Add `ui/Edt.kt` with:
  - `suspend fun onEdt(block: () -> Unit) = withContext(Dispatchers.EDT) { block() }`
    (thin wrapper that captures the right modality).
  - `suspend fun embedContainerIntoEditor(editor: Editor, container: JPanel, offset: Int)` —
    convert `embedContainerIntoEditor.kt` from `invokeLater` to a suspending function
    on `Dispatchers.EDT`.
- Remove the `invokeLater` import from `embedContainerIntoEditor.kt`.

### Step 3 — Make `FeatureGenerator.execute` suspending

- Change `FeatureGenerator.execute` signature:

  ```kotlin
  suspend fun execute(
      match: FeatureMatch,
      wrapper: TWrapper,
      project: Project,
  ): ProcessHandler?
  ```

  The `onProcessCreated` callback disappears — the caller receives the handler from
  the return value (or `null` when the feature has no process semantics).
- Update both adapters:
  - `ShellFeatureAdapter`: wrap `OSProcessHandler(commandLine)` creation in
    `withContext(Dispatchers.IO)` (constructor blocks on `Process.start()`). Inside
    the `catch (e: Exception)` block, switch to `Dispatchers.EDT` to print the error.
  - `HttpFeatureAdapter`: replace `sendAsync(...).whenComplete { ... }` with

    ```kotlin
    val response = runInterruptible(Dispatchers.IO) {
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
    withContext(Dispatchers.EDT) { console.clear(); printResponse(console, response) }
    ```

    The custom `ProcessHandler` becomes much smaller — `destroyProcessImpl` only
    needs to call `notifyProcessTerminated(0)`; the coroutine is cancelled by the
    caller via `Session.job?.cancel()`.

### Step 4 — Rewrite `ExecutionInlayProvider.run/stop/delete/toggle/refresh` on coroutines

- Replace each `invokeLater { ... }` body with `sessionStorage.cs.launch { withContext(Dispatchers.EDT) { ... } }`.
- The `run()` private function becomes:

  ```kotlin
  private fun run(...) {
      val previous = sessionStorage.replaceJob(key, sessionStorage.cs.launch { runImpl(...) })
      previous?.cancel()
  }
  private suspend fun runImpl(...) { /* uses withContext */ }
  ```

- `mountWrapperIntoContainer` → suspending function on `Dispatchers.EDT`, no longer
  schedules onto the next UI tick.
- `delete(key)` should `session.job?.cancelAndJoin()` from a coroutine before removing
  the container; the `try { destroyProcess() } catch (_: Throwable) {}` becomes
  obsolete because cancellation handles cleanup.
- `refreshInlays(editor)` becomes `suspend` and is called from inside the launched
  coroutine on `Dispatchers.EDT`.

### Step 5 — Convert `HttpRunState`/`HttpProcessHandler`

- `HttpRunState` is a one-shot `RunProfileState` returning an `ExecutionResult`. It
  cannot be a suspend function (called from the platform). Inside `startNotify()`,
  replace `thread { executeRequest() }` with a launch on a scope bound to the
  execution lifecycle. Two options:
  - **Preferred:** create a `RunContentDescriptor`-scoped scope by passing the
    project's `SessionStorage.cs` and storing the `Job` on the handler so
    `destroyProcessImpl` cancels it.
  - **Fallback:** the platform also exposes
    `service<MyCoroutineService>().cs` per project; route through `SessionStorage.cs`
    for consistency.
- Replace `httpClient.send(...)` with `runInterruptible(Dispatchers.IO) { ... }` so
  cancellation interrupts the blocking call.
- `printToConsole` no longer needs `invokeLater`; the surrounding coroutine already
  runs on `Dispatchers.EDT` between IO steps (or, for high-volume body output, do
  a single `withContext(Dispatchers.EDT) { ... }` after assembling the text).

### Step 6 — `computeMatches` off the EDT

(Stretch — not strictly an `invokeLater` site, but uncovered while reading
`ExecutionInlayProvider.collect`.)

- `collect` is called by the inlay infrastructure under a read lock on a background
  thread; the existing implementation is already safe but performs regex matching
  for every visited element. Defer expensive work to:

  ```kotlin
  smartReadAction(project) { computeMatches(file) }
  ```

  inside a coroutine started from a custom `InlayHintsCollector` or by moving the
  computation into a separate `BackgroundableTask`. Out of scope for this migration,
  but track as follow-up.

### Step 7 — Remove the `invokeLater` import everywhere

- After steps 1–5 no file imports `com.intellij.openapi.application.invokeLater`.
- Add a Detekt / IntelliJ inspection rule (or a CI grep) to fail the build when
  `invokeLater` reappears in `src/main/kotlin`.

## Service scope (SessionStorage)

`SessionStorage` is already a `@Service(Service.Level.PROJECT)`. The platform supports
injecting a `CoroutineScope` parameter into the constructor of a `@Service`-annotated
class; it is created from the parent project scope and cancelled automatically when
the project is disposed.

Target shape:

```kotlin
@Service(Service.Level.PROJECT)
class SessionStorage(
    val project: Project,
    val cs: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun getSession(key: String): Session? = sessions[key]
    fun putSession(key: String, s: Session) { sessions[key] = s }
    fun remove(key: String): Session? = sessions.remove(key)?.also { it.job?.cancel() }

    /** Replace any previously stored job and return it so the caller can join. */
    fun replaceJob(key: String, job: Job): Job? {
        val s = sessions[key] ?: return null
        val prev = s.job
        s.job = job
        return prev
    }
}
```

Cancellation chain:

- Project close → service scope `cs` cancelled → every active `runImpl` coroutine
  cancelled → `runInterruptible` propagates cancellation to the IO thread →
  HTTP request is interrupted → `finally` block on `NonCancellable + Dispatchers.EDT`
  publishes the final state to the inlay (or skips if the editor is already disposed).
- Rerun click → `replaceJob(...)` returns previous, caller calls
  `prev?.cancel()` before launching new coroutine.
- Stop click → `session.job?.cancel()`; the existing `destroyProcess()` on the
  `OSProcessHandler` remains the canonical way to terminate the shell process — the
  coroutine `join`s on the listener's `processTerminated` event.

## Risks

1. **Interop with `OSProcessHandler` and `ProcessListener`.** Process handlers
   already run their I/O on the platform-managed `BaseDataReader` threads. The
   coroutine wrapping must not race with `addProcessListener` — register the
   listener before calling `startNotify()` (current code already does this) and
   bridge `processTerminated` to a `CompletableDeferred<Int>` that the coroutine
   awaits. Mistakes here can leak processes.

2. **`InlayHintsProvider.collect` runs on a pooled read action.** Calling
   `runBlocking` inside it is forbidden. Make sure no suspend function is invoked
   from `collect` — only from click handlers (which run on the EDT and may launch
   coroutines on `sessionStorage.cs`).

3. **`EditorEmbeddedComponentManager.addComponent` requires the EDT *and* a valid
   editor.** When the project closes mid-launch, the editor may already be disposed.
   Wrap the `withContext(Dispatchers.EDT)` body in
   `if (editor.isDisposed) return@withContext`.

4. **`Dispatchers.EDT` vs `EDT(modalityState)`.** The default
   `Dispatchers.EDT` uses the current coroutine context's modality. For inlay
   refreshes triggered from a click in the editor, the default modality is
   correct. For UI launched from inside a modal progress dialog, capture
   `ModalityState.current().asContextElement()` at launch time.

5. **`HttpRunState` is invoked by the Run framework, not by us.** Its `execute`
   cannot suspend. The launched coroutine must outlive `execute()` and survive
   until the process terminates. Anchoring it to `SessionStorage.cs` is fine as
   long as the service is created lazily before `startNotify` is called — which it
   is, because the run is initiated from a click handler that already touched
   `SessionStorage`.

6. **Behavioural change: `mountWrapperIntoContainer` was previously *async*.** Any
   code that read `container.componentCount` immediately after calling
   `mountWrapperIntoContainer` would have seen `0`. Search for such callers; the
   migration makes it synchronous on the EDT. (`grep -n "componentCount" src` —
   none expected.)

7. **`runInterruptible` requires that the blocking call respects
   `Thread.interrupt()`.** `java.net.http.HttpClient.send` does (it throws
   `InterruptedException` wrapped in `HttpTimeoutException` / `IOException`). The
   `catch (e: Exception)` branch in `HttpFeatureAdapter` must treat
   `CancellationException` specially — *do not* print "Cancelled" to the console
   if the cancellation came from project shutdown.

8. **Kotlin coroutines version.** IntelliJ Platform 2025.1 bundles
   `kotlinx-coroutines-core` 1.8.x with the platform classloader. The plugin must
   not declare its own coroutines dependency; rely on the platform jar to avoid
   classloader splits. Confirm `build.gradle.kts` does not introduce a transitive
   `kotlinx-coroutines-core`. (Currently it does not.)

9. **`@Service` constructor with `CoroutineScope`.** This is a platform-managed
   parameter and only works on services annotated with `@Service`. The injection
   is supported in `pluginSinceBuild = 251` (we target 251) — no compatibility
   shim needed.

10. **`smartReadAction` / `readAction` semantics.** If `computeMatches` is moved
    off the EDT in step 6, it must hold a read action. PSI access without a read
    action throws on background threads.

## Verification

- **Build & inspections:**
  - `./gradlew build` succeeds.
  - `./gradlew verifyPlugin` (IntelliJ Platform Gradle Plugin) reports no
    threading violations.
  - `grep -rn "invokeLater" src/main` returns no results.

- **Existing unit tests:**
  - `./gradlew test` — no regressions in `HttpFeatureAdapterTest`,
    `ShellFeatureAdapterTest`, `ExecutionInlayProviderTest` (if present).

- **New tests to add:**
  - Coroutine cancellation: launch a "Run", project close → `Session.job.isCancelled`
    is true within the test timeout.
  - Rerun: clicking Run twice while the first is in flight must cancel the previous
    `Job` (assert `prev.isCancelled`).
  - `HttpFeatureAdapter` IO cancellation: stub `httpClient` with a slow handler,
    cancel the job, assert no console writes happen after cancellation.

- **Manual smoke (runIde):**
  1. Open `playground/` file with both `shell: echo hi` and `http://example.com`
     comments.
  2. Click *Run* on shell, observe inlay state transitions IDLE → RUNNING → FINISHED.
  3. Click *Run* on HTTP, immediately click *Stop* — process handler should
     terminate, inlay returns to FINISHED, no exceptions in `idea.log`.
  4. Click *Rerun*, then *Delete* — container is removed from the editor.
  5. Close the project while a request is in flight — `idea.log` shows
     `JobCancellationException` (not a thrown `IOException`), no leaked threads in
     thread dump.

- **Threading audit:**
  - Run IDE with `-Dide.slow.operations.assertion=true` and
    `-Dide.slow.operations.assertion.fast.suspending=true`. Every former
    `invokeLater` site should now produce no slow-operation warning.
  - Run with `-Dintellij.platform.write.intent.checks=true` to ensure no EDT call
    happens without a write-intent lock when required.

- **Linter / CI guard:**
  - Add a one-line CI step: `! grep -rn 'invokeLater' src/main/kotlin` to prevent
    regressions.
