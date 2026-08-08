# 01 — Migrate to declarative inlay hints / Code Vision

## Problem

Today the plugin renders Run/Stop/Rerun/Delete/Collapse buttons via the legacy
`com.intellij.codeInsight.hints.InlayHintsProvider` API. The implementation
lives in
[`src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt`](../../src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt),
registered per-language in:

- `src/main/resources/META-INF/language-kotlin.xml:3`
- `src/main/resources/META-INF/language-php.xml:3`
- `src/main/resources/META-INF/language-xml.xml:3`
- `src/main/resources/META-INF/language-yaml.xml:3`

Concrete problems:

1. **Legacy API + `@Suppress("UnstableApiUsage")`.**
   `ExecutionInlayProvider.kt:37-38` opts out of stability checks.
   `InlayHintsProvider<NoSettings>` has been the "old" Kotlin DSL flavour since
   2023.1; the modern replacement is
   `com.intellij.codeInsight.hints.declarative.InlayHintsProvider`
   (extension point `com.intellij.codeInsight.declarativeInlayProvider`).

2. **Internal API used to force re-render.**
   `ExecutionInlayProvider.kt:13` imports
   `com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal`, and
   `ExecutionInlayProvider.kt:266-271` calls
   `InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()`.
   This class is `*Internal` — it is not part of the public plugin SDK and can
   be renamed, removed or made `internal` between minor IDE updates. The plugin
   already targets `pluginSinceBuild = 251` (`gradle.properties:11`), so any
   2025.x change to this symbol breaks the only refresh path the inlays have.

3. **Inlay collector does heavy work in `collect`.**
   `computeMatches` (`ExecutionInlayProvider.kt:73-98`) walks every extractor +
   feature generator per pass. Re-collecting full file matches every keystroke
   is wasteful and amplifies the impact of (2).

4. **Mixed concerns — text presentation and Swing embedding share the same
   provider.** The Run button is text-shaped (drawn by the inlay), but the
   result panel is a Swing `JPanel` mounted through
   [`embedContainerIntoEditor.kt`](../../src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ui/embedContainerIntoEditor.kt)
   via `EditorEmbeddedComponentManager`. The provider therefore owns process
   lifecycle (`run` / `stop` / `delete` in `ExecutionInlayProvider.kt:184-258`),
   which makes migration to *any* declarative API non-trivial, because the
   declarative API explicitly forbids Swing inside the hint itself.

## Options considered

### (a) Declarative inlay hint for the button row + keep `EditorEmbeddedComponentManager` for the result panel

Replace `InlayHintsProvider<NoSettings>` with
`com.intellij.codeInsight.hints.declarative.InlayHintsProvider`. Each
button becomes a text token built with `PresentationTreeBuilder`
(text + clickable `InlayActionData`). Clicks are dispatched through
`com.intellij.codeInsight.hints.declarative.InlayActionHandler` (registered via
`com.intellij.codeInsight.inlayActionHandler`) with an `InlayActionPayload`
that carries `(featureId, line, action)`. The embedded result `JPanel` stays
exactly as it is — `EditorEmbeddedComponentManager` is the supported way to put
a `JComponent` into the editor and is **independent** of the inlay API.

- (+) Modern, supported API; no `UnstableApiUsage` opt-in.
- (+) Removes the need to call `InlayHintsPassFactoryInternal` — the
      declarative framework re-collects hints on document/PSI change
      automatically; for state changes we can call public
      `DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)`
      (note: `impl` package, two arguments) followed by
      `DaemonCodeAnalyzer.restart(file)`.
- (+) Sink supports both inline (`InlineInlayPosition`) and end-of-line
      (`EndOfLinePosition`) placement and accepts a `tooltip: String` argument
      directly on `addPresentation(position, payloads, tooltip, hasBackground,
      builder)`.
- (−) **Buttons become text-only.** `PresentationTreeBuilder` exposes only
      `text(...)`, `list(...)`, `collapsibleList(...)`, `clickHandlerScope(...)`
      and (on `CollapsiblePresentationTreeBuilder`) `toggleButton(...)`. There
      is **no `icon(...)` method** — verified by `javap` on
      `com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder` in
      the 2025.1.1 platform jar (`lib/app-client.jar`). We therefore lose
      `factory.icon(AllIcons.Actions.Execute)` and `factory.roundWithBackground`
      styling; buttons render as bracketed text tokens such as `[Run]`,
      `[Stop]`, `[Delete]`, `[Collapse]`. Background is controlled by the
      `hasBackground` boolean on `addPresentation` (or `HintFormat`).
- (−) Click handling becomes EP-routed: we cannot capture a lambda over
      `editor` / `match`, so all state for a click must be encoded in the
      payload + looked up via `SessionStorage`.

### (b) Use `DaemonBoundCodeVisionProvider` for the button row

Code Vision renders a *block* inlay above a PSI anchor, used by IDE for
"references", "implementations", run gutter, etc. Each entry is a clickable
text item handled by `handleClick(editor, element, event)` — the provider owns
the click. Registered via
`com.intellij.codeInsight.daemonBoundCodeVisionProvider`.

- (+) Public, stable, click handler runs with full `Editor` + `PsiElement` in
      scope (closer to today's lambda model).
- (+) `DaemonBound*` variant runs inside the highlighting daemon, so we get
      automatic recomputation on PSI changes — no manual refresh hack.
- (−) Code Vision lives **above** the line (block inlay). Today's UX is an
      *inline* inlay anchored to the offset of the match (`addInlineElement`,
      `ExecutionInlayProvider.kt:68`). Switching to block placement changes the
      look noticeably; users may dislike it.
- (−) Each Code Vision entry is one button; rendering Run + Stop + Delete +
      Collapse on one line means emitting several entries and they will be
      separated by the standard Code Vision spacing/separator.
- (−) Settings UI moves under *Settings | Editor | Inlay Hints | Code Vision*,
      not *Editor | Inlay Hints | <Language>*.

### (c) Stay on the old `InlayHintsProvider`, but drop the internal refresh call

Keep everything in `ExecutionInlayProvider.kt` and only replace
`InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()` with the public
`DaemonCodeAnalyzer.getInstance(project).restart(file)` (or
`restart()`, which is already called on the next line —
`ExecutionInlayProvider.kt:269`).

- (+) Smallest possible diff; one import + two lines change.
- (+) Zero risk of UX regression.
- (−) Does **not** fix the deprecated API surface. The old DSL is still marked
      `@ApiStatus.ScheduledForRemoval` in newer 2025.x builds; this only buys
      time.
- (−) `DaemonCodeAnalyzer.restart()` does not guarantee the inlay pass actually
      *re-runs* the collector when nothing in PSI changed. We may need a
      `PsiManager.dropPsiCaches()` or a synthetic
      `DaemonCodeAnalyzer.restart(file)` overload to be safe — verify on a
      live editor.

## Recommended approach

**Two-phase migration: (c) first as a fast hot-fix, then (a) as the proper
migration.**

Rationale:

- (c) is small, removable in one PR, and unblocks the immediate "internal API
  could disappear" risk. It can ship in the next patch release.
- (a) is the right long-term home: declarative inlays are the only API that
  the platform will keep evolving, the Swing embedding requirement is
  *orthogonal* to the inlay choice (we keep `EditorEmbeddedComponentManager`
  unchanged), and the click-via-EP model maps cleanly onto our existing
  `SessionStorage`-keyed state machine.
- (b) is rejected because the inline placement matters: the Run button must
  sit next to `// shell: echo hello`, not on a separate line above it. Code
  Vision will only be used if user feedback later asks for a more compact
  gutter-style affordance.

## Implementation steps

### Phase 1 — De-risk: drop internal API (option c)

1. Edit `ExecutionInlayProvider.kt:266-271` `refreshInlays`:
   - Remove the import of
     `com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal`
     (`ExecutionInlayProvider.kt:13`).
   - Replace the body with
     `DaemonCodeAnalyzer.getInstance(project).restart(file)`, where `file` is
     captured from `getCollectorFor(file, …)`
     (`ExecutionInlayProvider.kt:48-53`).
   - **Note.** The legacy (non-declarative) inlay pass for the old
     `InlayHintsProvider<NoSettings>` does re-run on `restart(file)` —
     `InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()` is only
     needed when the file's PSI/modification stamp has not changed AND we want
     to force the cache to be discarded. For our use case the inlay collector
     reads off-PSI state (`SessionStorage`); the daemon will still re-run the
     pass because `restart(file)` invalidates the daemon's per-file scheduling
     state. If this proves insufficient in practice (Phase 1 verification),
     fall back to capturing the produced `Inlay` references from the collector
     and calling `Inlay.update()` per match — this stays in public API.
2. Verify on the playground project that clicking Run → Stop transitions still
   re-render.
3. Keep `@Suppress("UnstableApiUsage")` — it is still required by the old DSL;
   it will be removed in Phase 2.

Deliverable: one commit, no behaviour change, no `*Internal` import.

### Phase 2 — Migrate to declarative inlay (option a)

#### 2.1 Introduce the new provider class

4. Create `src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionDeclarativeInlayProvider.kt`
   implementing `com.intellij.codeInsight.hints.declarative.InlayHintsProvider`
   (note: same simple name as the old class — keep them in separate files /
   packages until the old one is deleted).

   Required members:
   - `createCollector(file: PsiFile, editor: Editor): InlayHintsCollector?` —
     returns an `OwnBypassCollector` (we already do bypass collection in
     `computeMatches`, `ExecutionInlayProvider.kt:73-98`) **or** a
     `SharedBypassCollector` if we want PSI-element-level dispatch. Start with
     `OwnBypassCollector` to mirror current behaviour 1:1.
   - The collector's `collectHintsForFile(file, sink: InlayTreeSink)` iterates
     `matchesByElement` and calls
     `sink.addPresentation(InlineInlayPosition(offset, relatedToPrevious = false), hasBackground = false) { buildButtons(...) }`.

5. Replace the imperative `factory.icon` / `factory.text` / `factory.onClick`
   chain (`ExecutionInlayProvider.kt:100-182`) with the declarative
   `PresentationTreeBuilder` DSL. The builder is text-only — there is no
   `icon(...)` method — so each button is rendered as a labelled token:
   - `text("Run", InlayActionData(payload, RUN_HANDLER_ID))` for the Run
     button. Use plain ASCII labels: `Run`, `Stop`, `Delete`, `Collapse` /
     `Expand`, `Rerun`. Wrap in spaces and/or brackets (`[Run]`) so the click
     target is visually obvious.
   - Tooltip is supplied via the `tooltip: String` argument of
     `sink.addPresentation(position, payloads, tooltip, hasBackground, builder)`
     — *not* via `text(...)` (no per-token tooltip is exposed in the public
     `PresentationTreeBuilder`).
   - Background pill is controlled by `hasBackground = true` on
     `addPresentation`. Each button gets its **own** `addPresentation` call so
     each one has an independent tooltip and background, and so the platform
     does not insert a separator between them inside one presentation tree.

#### 2.2 Move clicks to `InlayActionHandler` extensions

6. Define a payload type, e.g. a Kotlin object with `Json`-style encoding:
   `data class ExecutionInlayPayload(val featureId: String, val line: Int, val action: Action)`,
   where `Action` is `RUN | STOP | DELETE | COLLAPSE`. Wrap into
   `StringInlayActionPayload(json)` (simplest) or implement a custom
   `InlayActionPayload`.

7. Create one `InlayActionHandler` per action:
   - `RunInlayActionHandler` (`handlerId = "inline_call.run"`)
   - `StopInlayActionHandler`
   - `DeleteInlayActionHandler`
   - `ToggleCollapseInlayActionHandler`

   Each handler reads `editor`, decodes the payload, looks up the session in
   [`SessionStorage`](../../src/main/kotlin/com/github/xepozz/inline_call/base/SessionStorage.kt),
   and performs the same logic that lives today in `run` / `stop` / `delete`
   / `toggleCollapse` (`ExecutionInlayProvider.kt:184-264`). Move that logic
   into a new `ExecutionController` service (`Service<Project>`) so handlers
   stay thin and the controller is reusable.

8. Register the handlers in `plugin.xml`:
   ```xml
   <extensions defaultExtensionNs="com.intellij">
     <codeInsight.inlayActionHandler
        handlerId="inline_call.run"
        implementationClass="…RunInlayActionHandler"/>
     <!-- stop, delete, toggle -->
   </extensions>
   ```
   These are language-agnostic, so they belong in
   `src/main/resources/META-INF/plugin.xml` (not in the per-language files).

#### 2.3 Re-register the provider per language

9. In each `META-INF/language-*.xml`, replace
   ```xml
   <codeInsight.inlayProvider language="…" implementationClass="…ExecutionInlayProvider"/>
   ```
   with
   ```xml
   <codeInsight.declarativeInlayProvider
        language="…"
        implementationClass="…ExecutionDeclarativeInlayProvider"
        providerId="inline_call.execution"
        bundle="messages.CallBundle"
        nameKey="inlay.execution.name"
        group="OTHER_GROUP"/>
   ```
   Files to update:
   - `src/main/resources/META-INF/language-kotlin.xml:3`
   - `src/main/resources/META-INF/language-php.xml:3`
   - `src/main/resources/META-INF/language-xml.xml:3`
   - `src/main/resources/META-INF/language-yaml.xml:3`

10. Add the new `inlay.execution.name` and `inlay.execution.description` keys to
    `src/main/resources/messages/CallBundle.properties` (referenced from
    `plugin.xml:14`). Required because `declarativeInlayProvider` validates
    `nameKey` at startup.

#### 2.4 Refresh on state change without `*Internal`

11. From `ExecutionController`, after mutating session state, call
    `DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)`
    (public, in `com.intellij.codeInsight.hints.declarative.impl`,
    **two arguments** — editor and project). Always follow with
    `DaemonCodeAnalyzer.getInstance(project).restart(psiFile)` because session
    state lives off-PSI and the daemon would otherwise see no modification
    stamp change and skip the pass. Wrap both calls in `invokeLater { … }` —
    these are EDT-bound APIs and we may be called from a process listener
    thread (see `processTerminated` in the original
    `ExecutionInlayProvider.kt:227`).

#### 2.5 Decommission the old provider

12. Delete `ExecutionInlayProvider.kt`
    (`src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ExecutionInlayProvider.kt`)
    once the declarative version is wired up and verified.
13. Remove the `@Suppress("UnstableApiUsage")` annotation — the declarative API
    is stable.
14. Update `CHANGELOG.md` under `[Unreleased]` with the API migration note.

## Risks

- **Tooltip / icon rendering parity.** The declarative API renders text + icon
  in a different visual style than `factory.roundWithBackground(...)`
  (`ExecutionInlayProvider.kt:139`). Buttons may look like coloured tokens
  instead of pill-shaped affordances. Acceptable, but expect screenshot diff in
  README.
- **Click coalescing.** Multiple `addPresentation` calls at the **same offset**
  may be merged or separated with a separator dot by the platform. Verify with
  the IDLE + FINISHED + RUNNING transitions where we currently chain 1–3 inlay
  parts (`ExecutionInlayProvider.kt:111-172`).
- **Payload size limits.** `StringInlayActionPayload` is transported as a
  string in the editor model. Keep payload < ~200 bytes. The current key model
  (`makeKey(editor, featureId, line)`, `ExecutionInlayProvider.kt:275`) is
  already compact, but `editor.hashCode()` won't survive an IDE restart — this
  is already tracked as
  [`05-session-storage-key.md`](./05-session-storage-key.md). The migration
  should *not* fix that key bug, but should also *not* make it worse.
  Concretely: encode the payload as `"$featureId|$line"` (action name is
  carried by the `handlerId` of `InlayActionData`). The handler must
  reconstruct the same `makeKey(editor, featureId, line)` string using the
  `editor` argument passed by `InlayActionHandler.handleClick(editor,
  payload)` so the hash-based key matches the one written by the collector.
- **Per-project EP vs application EP for handlers.**
  `inlayActionHandler` is application-level, while
  [`FeatureGenerator.EP_NAME`](../../src/main/kotlin/com/github/xepozz/inline_call/base/api/FeatureGenerator.kt#L36)
  is `ProjectExtensionPointName`. Handlers therefore have to fetch the project
  from `editor.project` and look up features via
  `FeatureGenerator.getApplicable(project)` — same pattern as today
  (`ExecutionInlayProvider.kt:101`).
- **PSI-bound collector vs OwnBypassCollector cost.** If the file has many
  matches and we re-walk all extractors on every collection
  (`computeMatches`, `ExecutionInlayProvider.kt:73-98`), the declarative pass
  budget (~50 ms) can be exceeded. Mitigation: cache results per
  `(file, modificationStamp)` inside the collector or move to
  `SharedBypassCollector` and dispatch by `PsiElement`.
- **Tests.** There are no inlay-level tests in the repo today. Adding
  `BasePlatformTestCase`-based tests for the new provider is *strongly*
  recommended but covered by a separate plan; this migration only needs the
  manual verification below.

## Out of scope (defer)

- Fixing the unstable `SessionStorage` key — see
  [`05-session-storage-key.md`](./05-session-storage-key.md).
- Replacing `invokeLater` with coroutines in
  `ExecutionInlayProvider.kt:237-263` — see
  [`03-coroutines-migration.md`](./03-coroutines-migration.md).
- Migrating the **embedded result panel** away from
  [`EditorEmbeddedComponentManager`](../../src/main/kotlin/com/github/xepozz/inline_call/base/inlay/ui/embedContainerIntoEditor.kt).
  That API is still the supported route for putting Swing into the editor;
  there is no declarative replacement.
- Adding a Code Vision provider for users who want a gutter-style "Run" affordance
  — possible follow-up, separate plan.
- Configuration UI (the current `ImmediateConfigurable` is empty,
  `ExecutionInlayProvider.kt:44-46`). The new declarative API uses
  `InlayHintsCustomSettingsProvider` if needed; skip until we have a real
  setting to expose.
- Removing the `@Suppress("UnstableApiUsage")` in Phase 1 — only Phase 2
  removes it.

## Verification

Manual smoke test on `playground/` (`ls /home/user/inline-call-plugin/playground`):

1. Build: `./gradlew :buildPlugin` from `/home/user/inline-call-plugin`.
2. Launch sandbox IDE: `./gradlew :runIde`. Open a file from `playground/`
   that contains a `// shell: …` or `// https://…` comment.
3. **IDLE state.** Expect a Run button next to the comment. Hover shows the
   tooltip "shell: …" (or "http: …"). Cursor turns into a hand.
4. **RUNNING state.** Click Run; the result panel embeds below the line
   (`embedContainerIntoEditor`), the inline button row switches to
   `Stop` + `Delete` (`ExecutionInlayProvider.kt:124-135` parity).
5. **FINISHED state.** When the process terminates, the row becomes
   `Rerun` + `Delete` + `Collapse`. Click `Collapse` — the panel hides; click
   again — it shows. Click `Rerun` — the process restarts and the row goes
   back to `Stop` + `Delete`.
6. **Delete.** Click `Delete` — the panel disappears and the row resets to
   `Run` only.
7. **Per-language coverage.** Repeat steps 3–6 in `.kt`, `.php`, `.xml`,
   `.yaml` files to confirm each `language-*.xml` registration was migrated.
8. **No internal-API regressions at startup.** In the sandbox IDE, *Help |
   Show Log in Files* — grep for
   `InlayHintsPassFactoryInternal`, `NoClassDefFoundError`,
   `IllegalAccessError`. None should appear.
9. **Settings UI.** *Settings | Editor | Inlay Hints* — the entry "Call
   (Unified)" should appear under each migrated language with the new
   `nameKey`. Toggling it off must hide all Run buttons.

Automated checks:

10. `./gradlew :verifyPlugin` — IntelliJ Plugin Verifier must not report
    references to `*Internal` classes or to the old `inlayProvider` EP.
11. `./gradlew :test` — existing unit tests in `src/test/kotlin` must still
    pass (the migration does not touch extractors or feature generators).
12. `./gradlew :runPluginVerifier` against `platformVersion=2025.1.1` and at
    least one EAP build to confirm forward compatibility — this is the whole
    point of the migration.

Definition of done:

- No `InlayHintsPassFactoryInternal` import anywhere in `src/`.
- No `@Suppress("UnstableApiUsage")` referencing the inlay provider.
- All four `language-*.xml` files use `codeInsight.declarativeInlayProvider`.
- All four button states (IDLE / RUNNING / FINISHED + collapse) work in the
  sandbox IDE for every supported language.
- Plugin Verifier is green against the current `platformVersion` and the next
  EAP.
