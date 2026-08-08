# 04 — Real OffsetMapping for multi-line and decorated comments

Status: refined after self-review.
Scope: `base/api/OffsetMapping.kt`, `base/api/BaseLanguageTextExtractor.kt`,
language extractors (`language/{php,xml,yaml}/*Extractor.kt`), feature
adapters (`feature/{shell,http}/*Adapter.kt`).
Out of scope: heredoc/nowdoc, string concatenation, template literals,
XML CDATA, kotlin `""" ... """`.

## PSI facts verified before implementation

- `com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment`
  extends `com.intellij.psi.PsiDocCommentBase`, which in turn extends
  `com.intellij.psi.PsiComment`. So the existing
  `PsiTreeUtil.findChildrenOfAnyType(file, PsiComment::class.java)` walk
  in `BaseLanguageTextExtractor.extract()` already yields PHPDoc nodes —
  no PSI traversal changes needed.
- `com.jetbrains.php.lang.lexer.PhpTokenTypes` exposes both `LINE_COMMENT`
  and `C_STYLE_COMMENT`. Per-element dispatch in PHPLanguageExtractor
  branches on the PhpDocComment class first (it would otherwise also
  match `C_STYLE_COMMENT` because the lexer wraps PHPDoc tokens too).
- `element.text.findTextRange(text)?.shiftRight(element.textOffset)` in
  the current base extractor is a tautological `TextRange(0, len)` lookup
  followed by a shift, equivalent to `element.textRange`. Plan keeps the
  existing call for diff-minimality; cleanup is out of scope.

## Problem

`OffsetMapping.Identity` assumes `block.text[i]` corresponds 1:1 to
`document.getText(block.originalRange)[i]`. `BaseLanguageTextExtractor.extract()`
builds the block from `element.text` verbatim (with comment decoration) and
hard-codes `OffsetMapping.Identity`. Both adapters do
`startOriginal = base + block.mapping.toOriginal(m.offset)` —
correct only while the normalized text equals the original slice.

This is correct today by accident: regexps (`shell:\s*(.+)`, the URL
pattern) match in the *middle* of comment text where decoration has already
been skipped by leading whitespace in the pattern itself, so no
normalization is needed and identity holds. The bug is *latent*: as soon as
we want any of the features below, identity breaks.

1. **PHPDoc `/** ... */` with `*` line prefix**. To match `shell:` on a
   line written `* shell: echo 1`, the natural fix is to strip
   `^\s*\*\s?` from each interior line so the regexp sees clean
   `shell: echo 1`. Then `m.offset` in the stripped text is N characters
   ahead of the document position; identity points the inlay at a `*`.
2. **XML comments `<!-- ... -->`**. A URL at the very start
   (`<!--https://...-->`) needs `mapping.toOriginal(0) == 4`. Identity
   highlights the opening bracket.
3. **YAML multi-line `#` blocks**. Several `#` lines glued into one block
   need `# ` stripped from every line; offsets shift by `2 * line_index`.
4. **PHP `//cmd` (no space)** — works today by coincidence; relies on the
   fact we do not strip `//`. The hidden coupling will fail the moment we
   reuse the same normalization pipeline for PHPDoc.

## Reproduction

`playground/1.php`:

```php
/**
 * shell: echo 123
 */
```

Steps:
1. Open in IDE; observe shell inlay on line 2.
2. The current matcher sees `* shell: echo 123` and matches starting at
   offset 5 (after `* `). Identity maps 5 -> 5 in the document. The
   document also has `* ` there, so the inlay lands at the right place by
   coincidence.
3. To make this matcher robust we want to **strip the `* ` prefix** so
   `shell:\s*(.+)` matches even if the author writes `*shell: cmd` (no
   space). After stripping, `m.offset == 0` in normalized text but the
   correct document offset is 5. Identity now points the inlay at `*`.

Fast failure repro once stripping is enabled:

```php
/**
 * Line one
 * shell: echo 42
 */
```

Normalized: `Line one\nshell: echo 42`. Match at normalized offset 9; raw
text has `shell` at document offset 16 (3 for `/**`, 1 newline, 4 spaces +
`* `). Identity: 9. Correct: 16.

`playground/1.yaml`:

```yaml
  # shell: echo 123
```

Single line, identity holds. Adding a sibling `# https://example.com`
produces two independent `PsiComment`s; still fine. Identity breaks only
once we glue them into one block (Step 7) or once we strip `# ` from a
single line (Step 5).

## Mapping model

Requirements:
- `toOriginal(normalizedOffset)` cheap and correct for every offset the
  matcher emits (match start and `start + value.length`).
- `toNormalized(originalOffset)` defined; clamp right when the original
  offset lands on a stripped char.
- Must handle `normalizedOffset == text.length` (end sentinel).

Chosen shape: **dense piecewise-linear mapping** built from a sorted list
of segments. Each segment is a contiguous run kept in both texts; gaps
between segments are stripped decoration in the *original* text.

```kotlin
class SegmentedOffsetMapping(
    private val segments: List<Segment>,
    private val normalizedLength: Int,
    private val originalLength: Int,
) : OffsetMapping {
    data class Segment(val normalizedStart: Int, val originalStart: Int, val length: Int)
}
```

- Sorted by `normalizedStart`; binary search is `O(log n)`.
- Inside segment `s`: `toOriginal(o) = s.originalStart + (o - s.normalizedStart)`.
- At `o == normalizedLength`: return
  `lastSegment.originalStart + lastSegment.length` (or `originalLength`
  when the mapping is empty). This makes `endOriginal = base +
  toOriginal(start + length)` span exactly the matched substring even
  when the original text continues with stripped decoration
  (e.g. PHPDoc closer).
- `toNormalized` on a stripped char clamps to the next segment's
  `normalizedStart`.

### Length handling

Today `endOriginal = startOriginal + m.value.length`. That stays correct
while each match lies inside one segment **and** `m.value` equals the
full match. Phase 1 patterns use `(.+)` (no newline), and all decoration
we strip is at line boundaries — so matches never cross gaps. Still,
**migrate adapters to**
`endOriginal = base + mapping.toOriginal(m.offset + m.value.length)` so
multi-line patterns are not a future trap. Add a debug assertion that
`endOriginal - startOriginal >= m.value.length`.

Caveat (latent, separate fix): `RegexpMatcher.findMatches` builds each
`MatchResult` as `(matcher.start(), matcher.group(1).trim())`. For the
shell pattern `shell:\s*(.+)`, `group(0)` is `shell: cmd` and `group(1)`
is `cmd`, so `m.offset` points at `shell:` while `m.value.length` is
only the trailing command length. End offset arithmetic
`m.offset + m.value.length` therefore lands mid-match today regardless
of this plan. Plan 04 does **not** fix this: it routes both ends through
the mapping unchanged. A follow-up should align `m.offset` with
`group(1)` or emit `valueRange` directly; the current adapters render an
inlay only at `startOriginal`, so the wrong `endOriginal` is invisible
in the UI but will bite anything that highlights the matched range
(actions, intentions, navigation).

### Why not a function or parallel `IntArray`s?

Function form cannot answer the inverse direction; serialization-unfriendly;
hard to debug. Parallel `IntArray`s are faster, worth the swap later
if profiling demands it — the public API does not change.

## Per-language strategy

Each language extractor returns the raw `PsiComment` text; a per-language
`CommentNormalizer` strips decoration and emits both the normalized text
and the segment list.

```kotlin
interface CommentNormalizer { fun normalize(rawText: String): NormalizedComment }
data class NormalizedComment(val text: String, val mapping: SegmentedOffsetMapping)
```

`BaseLanguageTextExtractor` ctor accepts a normalizer (default
`IdentityNormalizer`).

### XML (`<!-- ... -->`)

Strip leading `<!--` (4 chars) and trailing `-->` (3 chars). Optionally
strip one space after `<!--` and before `-->`. One segment for the inner
range. Multi-line XML comments have no per-line decoration, so one segment
is enough; interior indentation stays in normalized text and the regexp
handles `\s*` itself. CDATA is not a `PsiComment` (separate plan).

### PHP (`/** ... */`, `/* ... */`, `//`, `#`)

Dispatch on PSI element class inside `PHPLanguageExtractor.extract()`:

- `PhpDocComment` -> `CStyleBlockNormalizer(stripStarPrefix = true)`
- `PhpTokenTypes.C_STYLE_COMMENT` (`/* ... */`) -> `CStyleBlockNormalizer(stripStarPrefix = false)`
- line comment -> `SingleLineCommentNormalizer("//|#")`

PHPDoc normalization:
1. Strip leading `/**` and any whitespace up to and including the first
   `\n` (drop the `\n` too; first content line starts at normalized
   offset 0).
2. Strip trailing whitespace + `*/`.
3. Each interior line: strip `^\s*\*\s?` — at most one space after `*`,
   so indentation inside `@example` stays.
4. Emit each surviving `\n` as a 1-char segment between content segments.

Example. Raw `"/**\n * Line one\n * shell: echo 42\n */"` becomes
normalized `"Line one\nshell: echo 42"` with segments (illustrative):

```
(0,  7,  8)   // "Line one"
(8,  15, 1)   // "\n"
(9,  20, 14)  // "shell: echo 42"
```

Implementation: line-by-line scan, regex `^\s*\*\s?` anchored, two
cursors `originalCursor` and `normalizedCursor`. `\r\n` and `\n` both count
as one line terminator for stripping purposes; the original bytes still
contribute to `originalCursor` so document offsets stay accurate.

`//` and `#`: strip leading marker plus at most one space. One segment.

### YAML (`# ...`)

`SingleLineCommentNormalizer("#")`. Strip leading `\s*#\s?`. One segment.

**Do not glue** sibling `#` comments in Phase 1 — each `PsiComment` is one
block. Gluing is Step 7 (optional).

### Adapter (fallback)

`AdapterLanguageExtractor` uses `IdentityNormalizer`: single segment
`(0, 0, text.length)`. Behavior identical to today.

## Implementation steps

Each step is one commit; CI must pass at every step.

### Step 1 — `SegmentedOffsetMapping` (no behavior change)

Add `base/api/SegmentedOffsetMapping.kt` with `Segment`, binary-search
`toOriginal`/`toNormalized`, factory `SegmentedOffsetMapping.identity(length)`.
Unit tests in `SegmentedOffsetMappingTest`:
- identity round-trip;
- single-segment with leading gap (XML);
- multi-segment with per-line gaps (PHPDoc);
- boundary offsets (0, `length`, gap edges);
- `toNormalized` clamping on stripped chars.

`OffsetMapping.Identity` stays (back-compat).

### Step 2 — `CommentNormalizer` SPI + normalizers

Files: `base/api/CommentNormalizer.kt`, `base/normalizers/{Identity,SingleLine,CStyleBlock,XmlComment}Normalizer.kt`.

`SingleLineCommentNormalizer(markerRegex: String)`: strips
`^\s*(?:<marker>)\s?`. Note the non-capturing group around the marker —
the original `^\s*<marker>\s?` form has wrong precedence under
alternation (`//|#` would bind as `(^\s*//)|(#\s?)`). The normalizer
wraps the caller's marker in `(?:...)` to keep call sites simple.
`CStyleBlockNormalizer(stripStarPrefix: Boolean)`: handles `/* ... */`
and PHPDoc as described.
`XmlCommentNormalizer`: strips `<!--` / `-->` with one optional inner
space.

Unit tests per normalizer with hand-written fixtures plus a property test:
for every preserved index `i`, `original[mapping.toOriginal(i)] == normalized[i]`.

### Step 3 — Wire normalizer into `BaseLanguageTextExtractor`

Change ctor to `BaseLanguageTextExtractor(normalizer: CommentNormalizer = IdentityNormalizer)`.
In `extract()` call `normalizer.normalize(element.text)` and pass
`normalized.text` / `normalized.mapping` into `ExtractedBlock`.
`AdapterLanguageExtractor` keeps the default; behavior unchanged.

### Step 4 — Migrate adapters to map end-offsets via mapping

In `ShellFeatureAdapter` and `HttpFeatureAdapter`:

```kotlin
val startOriginal = base + block.mapping.toOriginal(m.offset)
val endOriginal   = base + block.mapping.toOriginal(m.offset + m.value.length)
```

Debug assertion: `endOriginal - startOriginal >= m.value.length`.

### Step 5 — Per-language wiring

- `XmlLanguageExtractor`: pass `XmlCommentNormalizer` to base ctor.
- `YamlLanguageExtractor`: pass `SingleLineCommentNormalizer("#")`.
- `PHPLanguageExtractor`: override `extract()` to dispatch by PSI class
  (PHPDoc / C-style / line) and pick the matching normalizer per element.

### Step 6 — Tidy up Identity usages

Mark `OffsetMapping.Identity` `@Deprecated("Use IdentityNormalizer")`.
Grep production code; no direct call sites must remain outside normalizers
and tests.

### Step 7 — Optional: YAML comment gluing

Detect contiguous same-column `PsiComment` siblings (no blank line, no
non-comment node between), build one block spanning all of them, normalize
each line. Defer until a real use case appears; touches the inlay
provider's per-comment anchoring assumption.

## Test plan

testData layout:

```
src/test/testData/extractors/
  php/
    line_comment.php
    line_comment.expected.txt
    block_comment.php
    block_comment.expected.txt
    phpdoc_single_line.php
    phpdoc_multi_line.php
    phpdoc_no_space_after_star.php
    phpdoc_crlf.php
  xml/
    single_line.xml
    multi_line.xml
    url_no_space.xml
  yaml/
    single.yaml
    indented.yaml
```

`*.expected.txt` records one block per fixture:

```
ORIGINAL:    "/** shell: echo 1 */"
NORMALIZED:  "shell: echo 1"
MATCH:       feature=shell value="echo 1"
             normalized=[0,13) original=[4,17)
```

Tests:

1. **Normalizer tests** (`*NormalizerTest`): parametrized over fixtures;
   assert normalized text and segment list.
2. **Extractor tests** (`PhpExtractorTest`, `XmlExtractorTest`,
   `YamlExtractorTest`) on `BasePlatformTestCase`: load fixture, run
   extractor, assert `block.text` and `block.originalRange` are correct.
3. **Adapter end-to-end** (`ShellAdapterMappingTest`,
   `HttpAdapterMappingTest`): load fixture, run adapter,
   assert `editor.document.getText(match.originalRange) == match.value` —
   this is the invariant Identity violates.
4. **Property test** in `SegmentedOffsetMappingTest`: generate random
   inputs with `*`/`#`/CRLF decoration, verify round-trip on every
   preserved index plus clamping on every gap index.
5. **Regression** for `playground/1.php`: load file, assert two shell
   matches at expected document offsets.

No harness changes; `MyPluginTest` already drives the existing fixture
folder. Add new fixtures next to it.

CRLF fixtures: do not check `phpdoc_crlf.php` in with literal `\r\n` —
local git autocrlf / `.gitattributes` configurations can rewrite it.
Construct the CRLF buffer in test code (`text.replace("\n", "\r\n")` on
a normal fixture) and feed it via `myFixture.configureByText`. Same
treatment for any YAML CRLF case if added later.

## Compatibility with sibling plans

- **01 (declarative inlay hints)**: provider reads
  `match.originalRange.startOffset`, calls
  `editor.document.getLineNumber(start)`, and addresses inlays via
  `addInlineElement(offset, ...)`. As long as `startOriginal` lands on a
  real document offset, the new mapping is transparent. No file in
  `base/inlay/` is touched.
- **02 (run configurations)**: feature adapters' `execute()` body is
  untouched; only `match()` end-offset arithmetic is rewritten. Run
  configs that consume `match.value` see no change.
- **03 (coroutines)**: no `invokeLater`/coroutine site is modified.
- **05 (session storage key)**: key derivation uses
  `editor.document.getLineNumber(start)`. The mapping makes `start`
  *more* correct (no longer pointing at decoration), but on existing
  passing fixtures the line number is identical — sessions stay sticky.

## Risks

- **Greedy `.+` and segment boundaries**. Phase 1 patterns never cross
  `\n`, so matches never cross stripped gaps. If a future pattern uses
  `(?s)` or `(.+?)\n.../s`, `m.value.length` can drift from
  `endOriginal - startOriginal`. Mitigation: Step 4 already routes end
  through the mapping; debug assertion catches regressions.
- **PHPDoc layouts in the wild are messy**. No space after `*`, multiple
  spaces, deep indent inside `@example`. Regex `^\s*\*\s?` strips at most
  one space; verify with Symfony / Laravel / Yii snippets in testData.
- **Performance**. Each `PsiComment` allocates a fresh
  `SegmentedOffsetMapping`. Acceptable for typical files; swap to
  `IntArray`-backed segments if profiling shows pressure. Single-segment
  cases can intern an `IdentityMapping` per length cache.
- **YAML gluing (Step 7) touches the inlay provider**. The provider
  anchors on a single `PsiComment`; a glued block spans multiple. Either
  pick the first comment as the anchor or teach the provider about
  multi-element blocks. Defer.
- **CRLF**. `PhpDocComment.text` on Windows-checked-out files contains
  `\r\n`. Normalizer must treat `\r\n` and `\n` uniformly for stripping
  but keep both bytes in `originalCursor`. Cover with `phpdoc_crlf.php`.
- **`toNormalized` contract**. For a stripped original offset we clamp
  right. Document this; callers must not assume round-trip equality on
  arbitrary inputs.
