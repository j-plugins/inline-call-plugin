# 02 — Register and fix Run Configurations

## Problem

The plugin ships two Run Configuration types — HTTP and Shell — fully implemented under:

- `src/main/kotlin/com/github/xepozz/inline_call/feature/http/run/`
- `src/main/kotlin/com/github/xepozz/inline_call/feature/shell/run/`

None of them are wired into `META-INF/plugin.xml`. The classes
(`HttpRunConfigurationType`, `ShellRunConfigurationType`,
`HttpRunConfigurationProducer`, `ShellRunConfigurationProducer`) compile but
are never instantiated by the platform — they are effectively dead code.
Naively adding a `<configurationType>` line is not enough: the existing code
has three latent defects that will become visible the moment the IDE starts
constructing these classes for real users.

Issues to address before registration:

1. `HttpSettingsEditor` and `ShellSettingsEditor` use unsound binding patterns
   that will silently lose user input on the next refactor.
2. Both `ConfigurationFactory` implementations override `getId()` to return
   the same string as the parent `ConfigurationType.getId()`. The platform
   uses `factory.id` to namespace serialized state inside a type; this
   collision triggers a warning and breaks the assumed invariant that the
   factory ID is a stable, human-readable name.
3. `HttpProcessHandler.startNotify()` spawns work with `kotlin.concurrent.thread { }`,
   bypassing the platform's pooled executor — bad for thread-naming, leak
   tracking, and unit-test determinism.
4. The persisted `ConfigurationType` ID becomes a forever-contract once
   published on the JetBrains Marketplace. We must lock the IDs in this PR
   and never rename them.

> **Relationship to plan 03 (coroutines migration).** Pre-requisite 3 here is
> an *interim* fix: we move off `kotlin.concurrent.thread` to
> `Application.executeOnPooledThread` so the very first marketplace release
> doesn't ship a non-daemon-thread leak. Plan 03 step 5 supersedes this with
> `runInterruptible(Dispatchers.IO) { ... }` launched on `SessionStorage.cs`.
> Plan 02 deliberately does **not** touch the `invokeLater`-based
> `printToConsole` (that is plan 03 territory) — only the thread-creation
> primitive is replaced.

## Pre-requisites (must fix BEFORE registration)

These three fixes are independent and can land in separate commits. They MUST
land before the `plugin.xml` registration commit, because once users have
saved configurations on disk we cannot change persistence shape without a
migration step.

### 1. SettingsEditor binding fixes

**HTTP (`HttpRunConfiguration.kt:55-108`)**

Current shape: `HttpSettingsEditor` declares five `private var` fields
(`url`, `method`, `headers`, `body`, `timeout`) and binds the UI to *those*.
`resetEditorFrom` copies config -> editor fields, `applyEditorTo` copies
editor fields -> config. Two problems:

- The UI is bound to scratch state, not to the configuration. If a future
  refactor removes `applyEditorTo` (or someone adds a new field and forgets
  to copy it), the Apply button silently writes defaults instead of the
  user's input.
- `bindText(::url)` on a `var url` declared inside the editor compiles but
  is semantically wrong: the Kotlin UI DSL expects the binding target to be
  the model the dialog represents, so that `isModified()` and `reset()` work
  correctly.

Fix: bind directly to a mutable snapshot model (a plain data class), and let
`applyEditorTo` / `resetEditorFrom` translate between that snapshot and the
`HttpRunConfigurationOptions`. Sketch of the target signatures:

```kotlin
private data class HttpUiModel(
    var url: String = "",
    var method: String = "GET",
    var headers: String = "",
    var body: String = "",
    var timeout: Int = 30,
)

class HttpSettingsEditor : SettingsEditor<HttpRunConfiguration>() {
    private val model = HttpUiModel()
    private lateinit var panel: DialogPanel

    override fun createEditor(): JComponent { /* panel { ... .bindText(model::url) ... } */ }
    override fun resetEditorFrom(s: HttpRunConfiguration) { /* copy s -> model; panel.reset() */ }
    override fun applyEditorTo(s: HttpRunConfiguration) { /* panel.apply(); copy model -> s */ }
}
```

The key invariant: `panel.apply()` is called before we copy `model -> s`, and
`panel.reset()` is called after we copy `s -> model`. This is the pattern
recommended in the Kotlin UI DSL v2 docs (bindings are applied on
`DialogPanel.apply()`).

**Shell (`ShellRunConfiguration.kt:69-92`)**

Current shape: `ShellSettingsEditor` uses raw `JTextField` and
`TextFieldWithBrowseButton` inside `cell(...)` — no binding at all. Values
are read manually in `applyEditorTo`. Also, `TextFieldWithBrowseButton` is
constructed but no `FileChooserDescriptor` is attached, so the Browse button
does nothing useful.

Fix:

- Switch to `textField()` / `textFieldWithBrowseButton(...)` factories from
  the Kotlin UI DSL v2 so they participate in `isModified()` / `reset()`.
- Bind through a snapshot model as in HTTP.
- Attach a `FileChooserDescriptorFactory.createSingleFolderDescriptor()` to
  the working-directory chooser. The DSL helper signature is
  `textFieldWithBrowseButton(project: Project? = null, fileChooserDescriptor: FileChooserDescriptor? = null, ...)`;
  pass the descriptor as a named argument.

### 2. `ConfigurationFactory.getId()` rename

`HttpRunConfigurationType.kt:37` and `ShellRunConfigurationType.kt:38` both
return the same constant as the parent type's ID
(`"HttpInlayRunConfiguration"` / `"ShellInlayRunConfiguration"`). The
platform uses `factory.id` as a child key during run-configuration
serialization. With a single factory per type the collision is technically
tolerable, but:

- The IDE logs a warning at registration time.
- If we ever add a second factory under the same type, persistence breaks
  with no obvious error.

Fix: return a short, human-readable, stable identifier that is unique inside
its `ConfigurationType`. Proposed values:

| Type                          | Factory `getId()` value | Notes                                  |
|-------------------------------|-------------------------|----------------------------------------|
| `HttpRunConfigurationType`    | `"HTTP"`                | persisted, never rename                |
| `ShellRunConfigurationType`   | `"Shell"`               | persisted, never rename                |

Important: changing `factory.id` *also* changes the serialization key, so
this has to ship in the same PR as the initial registration. After the first
public release the value is frozen forever.

Note on `ConfigurationFactory.getName()`: by default
`ConfigurationFactory.getName()` falls back to `getId()`, which is what the
*Run/Debug Configurations* wizard shows when a type has more than one
factory. Both of our types have a single factory, so the wizard shows the
type display name (`"HTTP Request"` / `"Shell Command"`) and the factory
name is never user-visible. We therefore leave `getName()` at its default
and let it return `"HTTP"` / `"Shell"` — readable enough if a future feature
adds a second factory under the same type.

While we are in these files, also fix
`ShellRunConfigurationType.getConfigurationTypeDescription()` — it currently
returns the literal string `"configuration type description"`.

### 3. ProcessHandler thread management

`HttpProcessHandler.startNotify()` (HttpRunState.kt:48-54) does:

```kotlin
override fun startNotify() {
    super.startNotify()
    thread { executeRequest() }
}
```

Problems with `kotlin.concurrent.thread`:

- Thread is non-daemon by default; it can prevent JVM shutdown in tests.
- No thread name -> hard to identify in profiler / thread dump.
- Not part of the platform's bounded pool -> bypasses limits and leak
  detection.

Fix: use `ApplicationManager.getApplication().executeOnPooledThread { ... }`
and store the returned `Future` so `destroyProcessImpl()` can `cancel(true)`
it. Sketch:

```kotlin
private var future: Future<*>? = null

override fun startNotify() {
    super.startNotify()
    future = ApplicationManager.getApplication().executeOnPooledThread { executeRequest() }
}

override fun destroyProcessImpl() {
    future?.cancel(true)
    notifyProcessTerminated(1)
}
```

Also wrap the `httpClient.send(...)` call so that an `InterruptedException`
from `cancel(true)` reports a clean "Cancelled" line on the console instead
of a stack trace. The existing `catch (e: Exception)` is broad enough to
catch it; we just need an earlier, more specific `catch (e: InterruptedException)`
(or `catch (e: HttpTimeoutException)` from the platform — `HttpClient.send`
maps thread interruption to `HttpTimeoutException` on Java 21 in some
implementations). Print a single `"\n[Cancelled]\n"` line, then fall
through to the `finally` that calls `notifyProcessTerminated`.

## Implementation steps

Each step = one atomic commit. Order matters; later steps depend on earlier
ones being green.

1. **Commit 1 — Fix `HttpSettingsEditor` binding.**
   Introduce `HttpUiModel`, switch UI DSL to bind against it, update
   `resetEditorFrom` / `applyEditorTo` to call `panel.reset()` /
   `panel.apply()`. No `plugin.xml` change.
2. **Commit 2 — Fix `ShellSettingsEditor` binding.**
   Same pattern as HTTP, plus attach a `FileChooserDescriptor` to the
   working-directory chooser.
3. **Commit 3 — Rename `ConfigurationFactory.getId()` returns.**
   `HttpConfigurationFactory.getId()` -> `"HTTP"`,
   `ShellConfigurationFactory.getId()` -> `"Shell"`. Fix the placeholder
   description string in `ShellRunConfigurationType`.
4. **Commit 4 — Pool the HTTP request thread.**
   Replace `kotlin.concurrent.thread` with `executeOnPooledThread`, store
   `Future`, cancel on destroy. Handle `InterruptedException`.
5. **Commit 5 — Register both types and both producers in `plugin.xml`.**
   See the XML snippets below.
6. **Commit 6 — Smoke test fixture (deferred to follow-up).**
   `LightPlatformTestCase`-based registration smoke tests would guard against
   the registration regressing, but adding the first one wires up the entire
   platform test fixture (heavy first run, longer CI). Tracked as follow-up
   in the next plan revision; out of scope for the PR that registers the
   types. `./gradlew :verifyPluginProjectConfiguration` already validates
   that the `<configurationType>` and `<runConfigurationProducer>` extension
   declarations are well-formed.

## plugin.xml registration

Add the following lines inside the existing
`<extensions defaultExtensionNs="com.intellij">` block (currently empty on
line 16-17):

```xml
<extensions defaultExtensionNs="com.intellij">
    <configurationType
        implementation="com.github.xepozz.inline_call.feature.http.run.HttpRunConfigurationType"/>
    <configurationType
        implementation="com.github.xepozz.inline_call.feature.shell.run.ShellRunConfigurationType"/>

    <runConfigurationProducer
        implementation="com.github.xepozz.inline_call.feature.http.run.HttpRunConfigurationProducer"/>
    <runConfigurationProducer
        implementation="com.github.xepozz.inline_call.feature.shell.run.ShellRunConfigurationProducer"/>
</extensions>
```

Notes:

- Both extension points live in the `com.intellij` namespace, so they go in
  the existing `defaultExtensionNs="com.intellij"` block (line 16), not in
  the second `defaultExtensionNs="com.github.xepozz.inline_call"` block.
- Do NOT add `id=` attributes to the `<configurationType>` lines; the ID
  comes from `ConfigurationType.getId()`.
- No `order=` attribute is needed for the producers; `LazyRunConfigurationProducer`
  is the canonical pattern and the platform handles ordering.

## Stable IDs (contract)

Once this PR is merged and released the following identifiers are frozen
forever. Renaming any of them silently breaks every saved run configuration
of existing users.

| Surface                          | Value                                | Source                                                    |
|----------------------------------|--------------------------------------|-----------------------------------------------------------|
| HTTP type ID                     | `HttpInlayRunConfiguration`          | `HttpRunConfigurationType.ID`                             |
| Shell type ID                    | `ShellInlayRunConfiguration`         | `ShellRunConfigurationType.ID`                            |
| HTTP factory ID                  | `HTTP`                               | `HttpConfigurationFactory.getId()` (new)                  |
| Shell factory ID                 | `Shell`                              | `ShellConfigurationFactory.getId()` (new)                 |
| HTTP options class FQN           | `...feature.http.run.HttpRunConfigurationOptions`   | persisted property names: `url`, `method`, `headers`, `body`, `timeout` |
| Shell options class FQN          | `...feature.shell.run.ShellRunConfigurationOptions` | persisted property names: `command`, `workingDirectory`                |

The marketplace pre-flight check: search
`grep -r "HttpInlayRunConfiguration\|ShellInlayRunConfiguration"` after
merge — should match only the two `ID` constants and their tests.

## Manual test plan

All steps run in a sandbox IDE launched via `./gradlew runIde`. Use a
throwaway project, not the plugin source repo.

### A. Registration smoke test

1. Open Run → Edit Configurations… → "+" (Add New Configuration).
2. Expected: "HTTP Request" appears in the list with the Web icon, "Shell
   Command" appears with the Run icon.
3. Pick "HTTP Request" → a blank editor opens with fields Method, URL,
   Timeout, Headers, Body. No exceptions in `idea.log`.
4. Pick "Shell Command" → editor opens with Command and Working Directory
   (Browse button visible). No exceptions in `idea.log`.

### B. HTTP — empty save / round-trip

1. Add new HTTP configuration, name it "manual-http-1".
2. Leave defaults (Method=GET, URL empty, Timeout=30).
3. Click Apply → no error dialog, OK to close.
4. Reopen Edit Configurations → "manual-http-1" is still present, all
   fields at defaults.
5. Restart the IDE (File → Invalidate Caches → Restart, or kill and
   relaunch the sandbox).
6. Reopen Edit Configurations → "manual-http-1" still present and intact.
   Confirms `HttpRunConfigurationOptions` round-trips through workspace.xml.

### C. HTTP — value persistence

1. Edit "manual-http-1": Method=POST, URL=`https://httpbin.org/post`,
   Timeout=15, Headers=`Content-Type: application/json`, Body=`{"x":1}`.
2. Apply, close dialog.
3. Reopen dialog → every field shows the value you just typed. If any
   field reverts to the default, the binding fix (step 1) regressed.
4. Modify URL in the dialog but click Cancel. Reopen → URL is unchanged.
   Confirms `panel.reset()` correctly discards uncommitted edits.

### D. HTTP — Modified indicator

1. With the dialog open and showing a saved config, the Apply button
   should be disabled.
2. Type a single character in URL → Apply lights up.
3. Press Ctrl+Z (or revert the character manually) → Apply goes back to
   disabled. This proves `bindText(model::url)` correctly drives
   `isModified()`.

### E. HTTP — Run

1. With the POST config from step C, click Run.
2. Console tab opens, prints `POST https://httpbin.org/post`, then
   `Connecting...`, then `HTTP 200` and the response body
   (pretty-printed JSON because the response is `application/json`).
3. Click the red Stop button mid-flight (use a slow endpoint like
   `https://httpbin.org/delay/10`). Expected: process terminates within
   ~1s, console shows the cancellation, no zombie thread in
   `jstack`/Thread dump (`Cmd+F1` → Thread dump in the sandbox IDE).
4. Run again — should work after stop, no "process already running"
   error.

### F. HTTP — Producer from comment

1. In the throwaway project, create `test.kt` with the comment:
   `// GET https://httpbin.org/get`
2. Right-click the line containing the comment → "Run …" should offer
   to run an HTTP request with name like `GET https://httpbin.org/get`.
3. Run it → console shows the response.
4. Right-click the same line again → instead of creating a new config,
   the gutter / context menu reuses the existing one (proves
   `isConfigurationFromContext` returns true).
5. Change the URL in the comment, right-click again → a new config is
   produced (proves the comparison is exact).

### G. Shell — Round-trip and Run

1. Add new "Shell Command" config, name it "manual-shell-1".
2. Command: `echo hello && pwd`. Working Directory: leave empty.
3. Apply, close, reopen → values intact.
4. Run → console prints `hello` followed by the project's base path
   (since `ShellRunState.startProcess` falls back to `project.basePath`).
5. Set Working Directory using the Browse button → pick an existing
   folder → Apply. Run again → `pwd` now shows the chosen folder.
6. Edit Working Directory to a non-existent path → Run → expected: clean
   error from `OSProcessHandler` in the console, no IDE-level exception.

### H. Shell — Producer from comment

1. Add the comment `// shell: ls -la` in `test.kt`.
2. Right-click the line → "Run …" offers a "Shell: ls -la" config.
3. Run it → console shows directory listing.

### I. Negative test — restart safety

1. Close all sandbox IDE windows.
2. Inspect `.idea/workspace.xml` of the throwaway project: confirm
   `<configuration ... type="HttpInlayRunConfiguration" factoryName="HTTP">`
   and `<configuration ... type="ShellInlayRunConfiguration" factoryName="Shell">`
   are written. The `factoryName` here is the NEW factory `getId()` —
   that is the regression guard for pre-requisite 2.
3. Manually corrupt one value (e.g. change `factoryName="HTTP"` to
   `factoryName="HttpInlayRunConfiguration"`) → relaunch IDE → confirm
   the platform logs a warning that no factory matches. (Then revert.)

### J. `idea.log` audit

After running steps A–I, open `Help → Show Log in Files`. Search the
session's log for `WARN` and `ERROR` entries containing
`HttpInlayRunConfiguration`, `ShellInlayRunConfiguration`, `HTTP`,
`Shell`, `configurationType`, `ConfigurationFactory`. Should be empty.

## Risks

- **ID freeze.** Once shipped, `HttpInlayRunConfiguration` and
  `ShellInlayRunConfiguration` are part of the plugin's persistence
  contract. A future rebrand cannot rename them without a
  `ConfigurationType` migration shim. Mitigation: add a one-line comment
  next to each `const val ID` warning future maintainers.
- **Factory ID change.** Renaming `factory.getId()` from
  `HttpInlayRunConfiguration` to `HTTP` will reject saved configurations
  that were created with the old factory ID. As of today there are no
  such users (the type was never registered), so this is safe — but it
  must land in the same release.
- **Bind-model refactor regressions.** The new snapshot-model pattern
  is mechanically simple but easy to get subtly wrong (e.g. forgetting
  `panel.apply()` before reading the model). Manual test plan steps D
  and C are specifically designed to catch this.
- **Pool starvation.** `executeOnPooledThread` shares the platform pool.
  If a user fires off many long-running HTTP requests they could starve
  other platform work. Acceptable for v1; revisit if reports come in.
- **Producer collisions.** The HTTP pattern is broad
  (`(?:VERB\s+)?https?://...`) and may match URLs inside Markdown
  comments or KDoc tags. Manual test F validates it works on a typical
  case; broader testing during dogfooding will tell.

## Verification

After implementing all six commits:

1. `./gradlew buildPlugin` — must succeed.
2. `./gradlew verifyPlugin` — must succeed with no new warnings for the
   two new configurationType / runConfigurationProducer entries.
3. `./gradlew runIde` — execute the full Manual test plan A–J.
4. `grep -n "configurationType\|runConfigurationProducer" src/main/resources/META-INF/plugin.xml`
   — must show exactly four lines (two types + two producers).
5. `grep -rn "kotlin.concurrent.thread" src/main/kotlin/com/github/xepozz/inline_call/feature/http`
   — must return no matches (confirms pre-requisite 3 landed).
6. `grep -n "configuration type description" src/main/kotlin`
   — must return no matches (confirms placeholder description was
   replaced).
7. Optional: install the resulting `.zip` from `build/distributions/`
   into a clean IDE installation and repeat tests A and E.
