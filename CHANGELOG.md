<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Call Changelog

## [Unreleased]

### Fixed

- Copy toolbar action now copies the actual console output instead of a hardcoded literal.
- HTTP result panel collapsed into a single console view; the previous three tabs all rendered the same component and two of them were empty.
- Shell execution no longer busy-polls the process handler from a `Task.Backgroundable` worker; lifecycle is delivered through `ProcessHandler` callbacks.
- Dropped optional `<depends>` entries that referenced missing config files (`language-json.xml`, `plugin-database.xml`, `plugin-dotenv.xml`).

### Removed

- `KotlinLanguageExtractor` (was a no-op; the default `AdapterLanguageExtractor` already covers Kotlin files).
- Debug `println` calls from `ExecutionInlayProvider`.


## [2025.1.0] - 2025-12-16

### Added

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

[Unreleased]: https://github.com/j-plugins/inline-call-plugin/compare/v2025.1.0...HEAD
[2025.1.0]: https://github.com/j-plugins/inline-call-plugin/commits/v2025.1.0
