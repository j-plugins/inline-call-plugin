# 05 — Stable session key and lifecycle

Status: refined (post self-review)
Owner: inline-call plugin
Touches: `base/SessionStorage.kt`, `base/inlay/Session.kt`,
`base/inlay/ExecutionInlayProvider.kt`, new
`base/lifecycle/SessionLifecycleListener.kt`,
`base/lifecycle/SessionPsiChangeListener.kt`,
`base/lifecycle/SessionEditorFactoryListener.kt`,
`META-INF/plugin.xml`

## Problem

Current key derivation:

```kotlin
fun makeKey(editor: Editor, featureId: String, line: Int): String =
    "${editor.hashCode()}_${featureId}_$line"
```

This composite key is wrong on four dimensions:

1. **Does not survive file reopen.** `editor.hashCode()` is the Swing/identity hash of
   the live `Editor` instance. Closing the tab disposes the `Editor`; reopening the
   same `VirtualFile` creates a new `Editor` with a different identity hash. The
   orphaned `Session` stays in the project-level `ConcurrentHashMap` but no
   future lookup will ever match it — the result panel still renders below the
   line (because the inlay was added to the old editor) but the user-visible
   button collapses back to `IDLE`.

2. **Does not survive split editor / multiple windows.** Opening the same file
   in a vertical split or in a detached window yields two distinct `Editor`
   objects with distinct hash codes. Clicking *Run* in one split does not
   change anything in the other — each pane has its own ghost session.

3. **`line` is computed from the live document offset.** In
   `buildActionsPresentation` we do
   `val line = editor.document.getLineNumber(start)`. If the user types a new
   line *above* the comment, every match below shifts down. The next
   recomposition computes a *different* key for the *same* logical match, so
   the existing `RUNNING` session becomes invisible and the button resets to
   `IDLE` — while the underlying `ProcessHandler` is still alive and writing
   into a detached panel.

4. **No cleanup.** `SessionStorage` is a `@Service(PROJECT)` holding
   `ConcurrentHashMap<String, Session>`. Nothing ever removes entries except the
   explicit *Delete* button. Closing an editor, closing a file, closing the
   project (until dispose), deleting the comment from source — all leak the
   `Session`, its `JPanel`, its `Wrapper`, and potentially a live
   `ProcessHandler`.

## Desired semantics

> "The same session" = the same logical *invocation site* in the user's source.

Concretely: if the user has `// https://api.example.com` at line 10 and clicks
*Run*, then:

- inserting a blank line above (now line 11) — **same session**, button stays
  *Stop* / *Rerun*;
- closing and reopening the file — **same session** if the comment text is
  unchanged and the process is still alive (or `FINISHED`);
- opening a vertical split — **both** splits show the same state, both *Stop*
  buttons control the one process;
- editing the URL to `https://api.other.com` — **new** logical site → fresh
  `IDLE` session; the old session (if still running) is auto-terminated
  because its anchor is gone;
- deleting the comment — old session terminated and removed;
- closing the project — everything terminated.

So the identity of a session is `(VirtualFile.url, featureId, normalized value)`,
not `(Editor instance, line)`.

## Options considered

### A) Key = `VirtualFile.url + featureId + hash(match.value)`

Pros:
- Survives editor reopen, splits and additional windows (shared by file).
- Stable under any edit that doesn't change the comment payload itself
  (inserting lines above, reformatting, renaming surrounding symbols).
- No PSI work — trivial to compute in `collect()`.

Cons:
- Changing the URL/command text (`https://a` → `https://b`) yields a new key;
  the old session orphans unless we actively reap it.
- Duplicate comments (same URL twice in the same file) collide. Need a
  disambiguator (occurrence index within file).
- Moving the project to a different path changes `VirtualFile.url` → sessions
  reset. Acceptable: rebuild is cheap, and we never had to persist anything.

### B) Key = `VirtualFile.url + featureId + PSI startOffset`

Same shape as (A) but uses offset instead of value hash.

Pros:
- Disambiguates two identical comments naturally.

Cons:
- Offset is *exactly* the thing that drifts under edits — equivalent to the
  current `line`-based bug. Strictly worse than (A) for our use case.

### C) `SmartPsiElementPointer<PsiComment>` as the anchor

Pros:
- IntelliJ tracks the PSI element across edits, including rename refactors
  and most reformatters. The pointer resolves to the same element as long as
  it physically exists.
- Naturally survives line shifts.

Cons:
- Doesn't help across file reopen unless we re-acquire pointers on file open
  (extra wiring with `FileEditorManagerListener`).
- After the comment is deleted, the pointer goes stale but the `Session` map
  entry persists — still need a reaper.
- More expensive to maintain than a string key; we'd carry pointers in
  values, not in keys (keys still need to be serializable for `Map` lookup).
- Doesn't naturally share state across split editors unless backed by a
  project-level store anyway.

### D) `Editor.putUserData(KEY, Map<String, Session>)`

Pros:
- Automatically GC'd with the `Editor` — no leak, no listener wiring.
- Trivial implementation.

Cons:
- Defeats the entire goal: state vanishes on reopen, splits don't share,
  multi-window doesn't share. We'd be solving the leak by amplifying bug #1
  and bug #2.

### E) `FileEditorManagerListener` + `EditorFactoryListener` as a janitor

Not an alternative key strategy — a complementary lifecycle layer. Listens
for `fileClosed`, `editorReleased`, `selectionChanged`, `beforeDocumentChange`
and tells `SessionStorage` to:

- terminate orphaned processes,
- dispose orphaned panels (the embedded `JComponent` is anchored to the
  disposed `Editor` and would NPE on touch otherwise),
- evict entries whose anchor PSI is gone.

Required regardless of which key option we pick.

## Recommended approach

**File-scoped key (A) + reaper listener (E), with `SmartPsiElementPointer`
inside the `Session` value as the anchor.**

Key shape:

```
${virtualFile.url}::${featureId}::${valueHash}#${occurrenceIndex}
```

- `virtualFile.url` survives editor lifecycle and is the same across splits
  and additional windows in the same project.
- `featureId` keeps `http` and `shell` matches with the same payload
  separate.
- `valueHash` — stable hash of `FeatureMatch.value` (the normalized
  command/URL text), not of the surrounding line. We keep this as a short
  hex hash (`Integer.toHexString(value.hashCode())`) for compactness; the
  paired `occurrenceIndex` and per-file scoping make accidental collisions
  innocuous (two distinct values with the same hash in the same file with
  the same `featureId`). See Risk #1 for the trade-off and the explicit
  decision to *not* embed the raw value in the key.
- `occurrenceIndex` — 0-based index of this match among matches in the same
  file with the same `(featureId, valueHash)`. Computed during
  `computeMatches` while we already iterate the file. Disambiguates
  duplicates without depending on offsets. Order is determined by
  **global** ascending `originalRange.startOffset` across the whole file
  (not per-PsiElement) so the index is deterministic regardless of which
  `PsiElement` contains the match.

Session value gains:

```kotlin
data class Session(
    val virtualFileUrl: String,
    val featureId: String,
    val valueHash: String,
    val occurrenceIndex: Int,
    var anchor: SmartPsiElementPointer<PsiElement>?,   // for liveness checks
    val container: JPanel?,
    var wrapper: Wrapper?,
    var state: ExecutionState = ExecutionState.IDLE,
    var processHandler: ProcessHandler? = null,
    var collapsed: Boolean = false,
)
```

`anchor` is used only by the reaper to decide "does this session still have
a comment in the file?". It is NOT part of the key — the key stays a pure
string so `ConcurrentHashMap` lookup remains O(1) and free of PSI access
from `collect()`.

Why this combo:
- Solves problems 1, 2, 3 (key independent of `Editor` and of physical line).
- Solves problem 4 via listener-driven cleanup, not via key cleverness.
- Keeps `collect()` pure-CPU (no PSI pointer dereference per repaint).
- Anchor-based reaping handles the "user deleted the comment while
  `RUNNING`" corner case cleanly.

## Implementation steps

1. **Extend `Session`** with `virtualFileUrl`, `featureId`, `valueHash`,
   `occurrenceIndex`, `anchor`. `container` is already nullable. Add
   `fun isAlive(): Boolean` that resolves the anchor under a read action
   (`SmartPsiElementPointer.element` requires read access) and returns
   `element != null && element.isValid`. Make the new mutable fields
   (`container`, `wrapper`, `state`, `processHandler`, `collapsed`,
   `anchor`) `@Volatile` — `PsiTreeChangeListener` (EDT) and
   `processTerminated` (worker thread) write concurrently; see Risk #4.

2. **Rewrite `makeKey`** as a top-level helper on `(VirtualFile, FeatureMatch,
   Int)` — drop the `Editor` parameter entirely:

   ```kotlin
   fun makeKey(file: VirtualFile, featureId: String, valueHash: String, occ: Int): String =
       "${file.url}::${featureId}::${valueHash}#${occ}"

   fun hashValue(value: String): String =
       Integer.toHexString(value.hashCode())  // collision rate is fine for our scale
   ```

3. **Compute `occurrenceIndex` in `computeMatches`.** After building
   `matchesByElement`, flatten everything into a single list sorted by
   ascending `originalRange.startOffset`. Walk it and assign each entry a
   running 0-based index keyed by `(featureId, valueHash)`. Store the
   result in an `IdentityHashMap<FeatureMatch, Int>` (sharing class with
   `matchesByElement` so `collect()` can look it up by reference).
   `FeatureMatch` stays unmodified — it's a public data class shared with
   future plans.

4. **`buildActionsPresentation`** no longer reads `editor.document.getLineNumber`
   for key computation. Line is still needed for `lineEndOffset` (where to
   mount the panel), but that's a UI concern, not an identity concern.

5. **`run()` upgrade path.** When mounting the result container, we now need
   to know which `Editor` to embed into. Two situations:
   - Click came from one of N split editors — embed into *that* editor. The
     other splits will pick up the session on next `collect()` and show
     *Stop* / *Rerun*, but will not get their own panel. (Acceptable for v1;
     see Risks.)
   - File reopened with `FINISHED` session — `container` is non-null but its
     parent `Editor` is disposed. Detect via `container.parent == null` or a
     dispose flag; rebuild the panel into the new `Editor` and re-mount the
     existing `wrapper.component`.

6. **`SessionStorage` API broadening:**

   ```kotlin
   fun getSession(key: String): Session?
   fun putSession(key: String, session: Session)
   fun remove(key: String): Session?

   // new
   fun sessionsForFile(url: String): List<Session>
   fun removeAllForFile(url: String): List<Session>
   fun forEach(action: (String, Session) -> Unit)
   ```

   Backing map can stay `ConcurrentHashMap<String, Session>`. The
   per-file queries scan; volume is small (one session per Run-click, not
   per match), so an O(N) scan is fine. If it isn't, add a secondary
   `ConcurrentHashMap<String, MutableSet<String>>` (url → keys).

7. **`FileEditorManagerListener` reaper** (new class
   `base/lifecycle/SessionLifecycleListener.kt`, wired via
   `<projectListeners>` in `plugin.xml`, topic
   `FileEditorManagerListener.FILE_EDITOR_MANAGER`):

   - `fileClosed(source, file)` — **first check** `source.getAllEditors(file)`.
     If the file is still open in another tab/window of the same project,
     do nothing (split close, not file close). Otherwise, for each session
     with that url:
     - if `state == RUNNING` → call `processHandler?.destroyProcess()` and
       set `state = FINISHED`. **Do not remove the entry** — user may
       reopen and want to see the result.
     - if `state == IDLE` → remove the entry; it never ran, nothing to
       preserve.
     - dispose the `JPanel` (it's anchored to a dead `Editor`), null out
       `container`. Next reopen will rebuild it from the still-attached
       `wrapper`.
   - `fileOpened(file)` — no-op; `collect()` will naturally restore the
     buttons via key match.

8. **`EditorFactoryListener.editorReleased`** — covers the split-close case
   without closing the underlying file. If this was the *only* editor for
   the file, behave like `fileClosed`. Otherwise just unbind any panel
   anchored to that specific editor (panels are 1-per-session today, so
   typically nothing to do).

9. **PSI-deletion reaper.** New `SessionPsiChangeListener` extending
   `PsiTreeChangeAdapter`, registered via
   `PsiManager.getInstance(project).addPsiTreeChangeListener(listener,
   sessionStorage)` so it disposes with the project service. On
   `childRemoved` / `childReplaced`, derive the affected file (`event.file
   ?: event.parent?.containingFile`), then iterate sessions for that
   file's url and check `session.isAlive()` under a read action. For any
   dead anchor:
   - terminate the process,
   - dispose the panel,
   - `sessionStorage.remove(key)`.

   This is also where the "user edited the URL" case is handled: the old
   anchor still points to the same `PsiComment`, but `collect()` on the
   next pass will compute a new `valueHash` and therefore a new key. The
   old key has no matching match anymore — sweep it via "session has no
   match in latest `computeMatches`" check (step 10), not via PSI delete.

10. **Stale-key sweep at end of `computeMatches`.** Cheap defence in depth:
    after computing all matches and `occurrenceIndex` values for the file,
    build the set of *valid* keys for this file and call
    `sessionStorage.sweepFile(url, validKeys)` which, for each session
    keyed by `url` not present in `validKeys`, terminates the process and
    disposes the panel before removing the entry. Runs on each inlay
    pass; the per-file scan is cheap (one session per Run-click, not per
    match). The sweep MUST be confined to keys for this single file's
    `url` — never touch sessions belonging to other files.

11. **`SessionStorage.dispose()`** — `SessionStorage` is a
    `@Service(PROJECT)` and therefore already disposed with the project.
    Implement `Disposable` and in `dispose()` terminate every session's
    process and drop the map. This is the last-resort cleanup; we don't
    need a separate `ProjectManagerListener.projectClosing` because
    project disposal disposes project services.

12. **Delete `makeKey(editor, featureId, line)`** signature, update all
    call sites. Compile error will catch any miss.

### Listener registration (plugin.xml)

```xml
<projectListeners>
    <listener
        class="com.github.xepozz.inline_call.base.lifecycle.SessionLifecycleListener"
        topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
</projectListeners>
```

`PsiTreeChangeListener` and `EditorFactoryListener` are wired
programmatically inside `SessionStorage` (or a tiny "starter" service
triggered on project open) because they need a `Disposable` parent
parameter and benefit from sharing the `SessionStorage` instance. The
`SessionStorage` service serves as the `Disposable` parent so that
unregistration is automatic on project close.

## Cross-plan dependencies

- **Plan 03 (coroutines).** Plan 03 will convert `SessionStorage` into
  `class SessionStorage(project, cs: CoroutineScope)` and add `Session.job:
  Job?`. To minimise merge friction this plan deliberately leaves the
  primary constructor untouched and adds *all* new mutable fields as `var`
  so plan 03 can append `var job: Job? = null` without rewriting any field
  in the data class. The `dispose()` we add here will, after plan 03,
  cancel the `cs` as well; for now it just terminates processes.
- **Plan 01 (declarative inlay hints).** Plan 01 owns the inlay UI
  pipeline. This plan only changes `makeKey` and the listener wiring
  inside `ExecutionInlayProvider`; the per-state UI builder code remains
  identical bytes-wise.
- **Plan 02 (run configurations).** No overlap. Run configurations don't
  touch `SessionStorage`.
- **Plan 04 (offset mapping).** This plan reads `FeatureMatch.value` (the
  normalised payload) which is exactly what plan 04 strengthens. Once
  plan 04 lands, `valueHash` becomes even more stable across
  PHPDoc-comment-style reformatting; no API change needed here.

## Migration

There is no persisted state — `SessionStorage` is in-memory only and dies with
the project. A plugin upgrade naturally drops all sessions, which is the
desired behaviour: nothing to migrate, nothing to corrupt.

If at some future point sessions become persistent (e.g. result history
across restarts), the file-scoped key from this plan serializes fine:
`url::feature::hash#occ` is a stable string. The PSI anchor would not be
persistable and would have to be re-acquired on project open by re-running
`computeMatches` for each cached key's file.

In-flight processes during upgrade: plugin reload kills the entire JVM
classloader scope for the plugin. Any `ProcessHandler` we hold disappears
along with us, and the spawned OS processes inherit nobody. This is a
pre-existing condition, not new with this change. Document it as known
limitation; do not attempt to re-attach on plugin reload.

## Lifecycle rules

| Event | Behaviour |
| --- | --- |
| **File closed while session is RUNNING** | Process is **terminated**, `state` set to `FINISHED`, `container` disposed, session kept in storage. Rationale: user expects "close the tab, stop the work"; standard IDE behaviour for run tools. Keeping the entry lets reopen show a *Rerun* button immediately. |
| **File closed while session is FINISHED** | Container disposed, session kept in storage. Reopen rebuilds the panel from the cached `wrapper` (still holding the output). |
| **File closed while session is IDLE** | Session removed (it never ran, nothing to preserve). |
| **File reopened (any state)** | `collect()` finds the matching key, shows the right button. On *Rerun*, `run()` detects null/orphan `container`, rebuilds it into the new editor, re-mounts the existing wrapper component if possible (else a fresh wrapper). |
| **Editor split (same file, second pane)** | Both panes share the session via the file-scoped key. The result panel exists in whichever editor first mounted it (typically the one where the user clicked *Run*). The other pane shows *Stop* / *Rerun* with no inline panel — clicking *Stop* affects the shared process. v1 limitation: no duplicate panel in the second pane (see Risks). |
| **Second IDE window for same file** | Same project → same `SessionStorage` → same behaviour as split. Different project (same file opened via "Open in New Window" into a different project) → different `SessionStorage`, sessions are independent. Acceptable. |
| **Comment deleted from file** | `PsiTreeChangeListener` detects the missing anchor → terminate process → dispose panel → `remove(key)`. Next `collect()` pass would also evict it via the stale-key sweep (defence in depth). |
| **Comment edited (URL/command changed)** | New `valueHash` → new key. The stale-key sweep at the end of `computeMatches` finds the old key has no current match → terminate process → dispose panel → remove. The new key shows up as `IDLE`. |
| **Comment moved (cut here, paste 20 lines down)** | If done as a single PSI-level move, `SmartPsiElementPointer` may follow it; the key is value-based so it stays the same regardless. Session preserved. If done as delete+retype, see "comment deleted" then "new IDLE session appears". |
| **Project closed** | `projectClosing` terminates every process, then service disposal drops the map. |
| **Plugin reloaded / IDE restarted** | All sessions lost (in-memory storage). Processes spawned by the plugin become detached OS processes; pre-existing limitation. |
| **Duplicate identical comments in one file** | `occurrenceIndex` (0, 1, 2…) disambiguates. Order is determined by `originalRange.startOffset` during `computeMatches` so it's stable across edits as long as relative order of matches doesn't change. Reordering identical matches will swap their sessions — acceptable edge case; the user would have to deliberately reorder identical URLs. |

## Risks

1. **Hash collisions on `valueHash`.** `String.hashCode()` collisions in a
   single file are vanishingly rare for realistic URL/shell payloads, but a
   hostile or unlucky case would alias two sessions. Mitigation: use full
   string in the key (`...::${value}#${occ}`) instead of a hash. Cost: key
   string length; benefit: zero collisions. Recommend going full-string
   unless we ever embed sessions into log lines where length matters.

2. **`occurrenceIndex` instability under reorder.** If the user swaps two
   identical lines, their sessions swap too. Trade-off is intrinsic to any
   index-disambiguated scheme; PSI-pointer disambiguation would fix it at
   the cost of much more bookkeeping. Acceptable for v1.

3. **Split editor: panel only in one pane.** Inlay model supports adding the
   same inline element in both editors, but `EditorEmbeddedComponentManager`
   binds the component to one specific editor. Fully replicating panels
   across splits requires per-editor `JPanel` instances driven from a shared
   `Wrapper` model — bigger refactor. v1: button state shared, panel
   single-pane.

4. **Reaper races.** `PsiTreeChangeListener` fires on EDT; `processTerminated`
   fires on a process worker thread. Concurrent `state` updates on the
   `Session` are already racy in current code. Add `@Volatile` to mutable
   `Session` fields and confine all map mutations to `SessionStorage`'s
   `ConcurrentHashMap` calls.

5. **`VirtualFile.url` for in-memory / scratch files.** Light virtual files
   produce synthetic URLs that change across sessions; some are
   `temp:///...`. Sessions in scratches won't survive IDE restart. Document;
   not worth fighting.

6. **Renamed / moved files.** `VirtualFile.url` changes on move; pointer
   identity does not. Sessions detach on file rename. Optional follow-up:
   subscribe to `VirtualFileListener.fileMoved` and rewrite keys. Probably
   not worth it — the user can just click *Rerun*.

## Verification

Manual scenarios; each should be reproduced before merging.

1. **Line shift.**
   - Open a PHP file with `// https://example.com/users` on line 10.
   - Click *Run*; verify *Stop* appears.
   - Press Enter at line 1 five times.
   - Expected: button on line 15 is still *Stop* (or *Rerun* if finished),
     panel is still mounted under the comment.

2. **File reopen, FINISHED.**
   - Run a quick request, wait for *Rerun* to appear.
   - Close the editor tab.
   - Reopen the same file.
   - Expected: *Rerun* (and *Delete*) buttons present, panel re-renders
     beneath the comment with the previous response body.

3. **File reopen, RUNNING.**
   - Run a long shell command (`sleep 30`).
   - Close the editor tab.
   - Expected: command is **killed** within ~1s (`ps` shows no `sleep`).
   - Reopen file → button shows *Rerun*, panel shows whatever output was
     captured before termination.

4. **Split editor.**
   - Open file, run a long shell command.
   - Right-click tab → Split Right.
   - Expected: both panes show *Stop* at the comment.
   - Click *Stop* in either pane → process dies, both panes show *Rerun*.

5. **Comment deleted while running.**
   - Run `sleep 30`.
   - Select the comment line and delete it.
   - Expected: process killed within ~1s, no orphan panel, no inlay.

6. **Comment edited.**
   - Run for `https://a.example.com`.
   - Edit URL to `https://b.example.com`.
   - Expected: old session terminated, panel removed, new *Run* button
     appears for the new URL, old session entry evicted from storage
     (verify via debug log of `sessionStorage` size).

7. **Duplicate comments.**
   - Put two identical `// shell: echo hi` lines in one file.
   - Run the first only.
   - Expected: first shows *Rerun*, second still shows *Run*. Run the
     second, both have independent panels.

8. **Multiple windows, same project.**
   - Window > New Window for the same project.
   - Open the same file in both.
   - Run in window A.
   - Expected: window B's button updates to *Stop* on next inlay refresh
     (may need a keystroke to trigger).

9. **Project close with active process.**
   - Run `sleep 60`.
   - Close project.
   - Expected: `sleep` process gone within ~1s; no leaked Swing components
     in `ReferenceQueue` (verify with `Diagnostics > Find Memory Leaks` in
     IDE devkit).

10. **IDLE not persisted.**
    - Open file with five matchable comments; click none.
    - Close file.
    - Expected: `sessionStorage.sessionsForFile(url)` returns empty list
      (verify via temporary log).
