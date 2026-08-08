<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Call Changelog

## [Unreleased]

### Changed

- Run / Stop / Rerun / Delete / Collapse buttons now use the declarative inlay
  hints API (`com.intellij.codeInsight.hints.declarative.InlayHintsProvider`)
  instead of the legacy `InlayHintsProvider<NoSettings>` DSL. Click handling
  is dispatched through application-level `InlayActionHandler` extensions.
  Buttons are text-only (`[Run]`, `[Stop]`, `[Delete]`, `[Collapse]`,
  `[Expand]`, `[Rerun]`) because `PresentationTreeBuilder` exposes no icon API
  in the public surface.
- Setting moved from `Settings | Editor | Inlay Hints | <Language> | Call (Unified)`
  to `Settings | Editor | Inlay Hints | Other | Call (Unified)` (the platform's
  declarative inlay framework groups providers by `InlayGroup`).

### Fixed

- Copy toolbar action now copies the actual console output instead of a hardcoded literal.
- HTTP result panel collapsed into a single console view; the previous three tabs all rendered the same component and two of them were empty.
- Shell execution no longer busy-polls the process handler from a `Task.Backgroundable` worker; lifecycle is delivered through `ProcessHandler` callbacks.
- Dropped optional `<depends>` entries that referenced missing config files (`language-json.xml`, `plugin-database.xml`, `plugin-dotenv.xml`).
- Inlay refresh no longer depends on `com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal`
  (an `*Internal` symbol that can be renamed or removed between 2025.x updates).
  Refresh now goes through the public `DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)`
  + `DaemonCodeAnalyzer.restart(file)`.

### Removed

- `KotlinLanguageExtractor` (was a no-op; the default `AdapterLanguageExtractor` already covers Kotlin files).
- Debug `println` calls from `ExecutionInlayProvider`.
- Legacy `ExecutionInlayProvider` (`InlayHintsProvider<NoSettings>` + `FactoryInlayHintsCollector`).
  Replaced by `ExecutionDeclarativeInlayProvider`. The `@Suppress("UnstableApiUsage")`
  opt-in is gone from the inlay code path.


## [2025.1.0] - 2025-12-16

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

[Unreleased]: https://github.com/j-plugins/inline-call-plugin/compare/v2025.1.0...HEAD
[2025.1.0]: https://github.com/j-plugins/inline-call-plugin/commits/v2025.1.0
