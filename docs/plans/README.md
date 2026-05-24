# Out-of-scope plans

Эти задачи были вынесены за рамки PR с гигиеническими правками
(см. ветку `claude/plugin-capabilities-analysis-u3S7Y`). Каждый файл —
независимый план изменения; берётся в работу отдельным PR.

| Файл | Тема |
|------|------|
| `01-declarative-inlay-hints.md` | Миграция с `InlayHintsProvider` + `InlayHintsPassFactoryInternal` на declarative API (или Code Vision) |
| `02-run-configurations.md` | Регистрация `HttpRunConfigurationType` / `ShellRunConfigurationType` после починки `SettingsEditor` биндингов |
| `03-coroutines-migration.md` | Замена `invokeLater` / `Task.Backgroundable` на корутины (`Dispatchers.EDT`, `withContext`, `readAction`) |
| `04-offset-mapping.md` | Реальная реализация `OffsetMapping` для языков с экранированием/heredoc/конкатенацией |
| `05-session-storage-key.md` | Стабильный ключ `SessionStorage` (сейчас `editor.hashCode()` теряется при reopen) |
