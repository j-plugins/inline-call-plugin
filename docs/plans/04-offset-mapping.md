# 04 — Real OffsetMapping for multi-line and decorated comments

Status: draft
Scope: `base/api/OffsetMapping.kt`, `base/api/BaseLanguageTextExtractor.kt`,
language-specific extractors (`language/{php,xml,yaml}/*Extractor.kt`).
Out of scope: heredoc/nowdoc, string concatenation, template literals.

## Problem

`OffsetMapping.Identity` assumes `block.text[i]` corresponds to
`document.getText(block.originalRange)[i]` — i.e. the normalized text fed to
the regexp matcher is byte-for-byte the same slice of the document that the
inlay/highlighter will later use via `originalRange.startOffset + match.offset`.

`BaseLanguageTextExtractor.extract()` today builds the block from
`element.text` verbatim:

```kotlin
val text = element.text                          // PHPDoc: "/**\n * shell: echo 1\n */"
val textRange = element.text.findTextRange(text)?.shiftRight(element.textOffset)
ExtractedBlock(element, textRange, text, OffsetMapping.Identity)
```

Both `ShellFeatureAdapter` and `HttpFeatureAdapter` (lines 33 and 43
respectively) then do:

```kotlin
val startOriginal = base + block.mapping.toOriginal(m.offset)
```

That assignment is correct only while the normalized text equals the original
text. The moment we want to strip comment decoration to make the regexp robust,
identity breaks.

Concrete breakage scenarios:

1. **PHPDoc `/** ... */`** — each line begins with optional whitespace plus
   `*` plus optional space. If we strip the `*` so that `shell:\s*(.+)` matches
   `shell: echo 123`, the offset in the stripped text is N characters earlier
   than in the document text. Currently the regexp *does* match (because `*`
   is not in the way of `shell:`), but `m.value` ends up containing trailing
   junk (`echo 123\n */`) because `(.+)` is greedy and `*/` is on the next
   chunk after `\n`. The dot does not cross `\n`, so on the multi-line
   PHPDoc the captured value is `echo 123` followed by trailing whitespace
   that `trim()` removes — by accident this looks fine. The bug surfaces as
   soon as we want to support **multi-line values** or **strip leading `*`**
   so `shell:` works when written `* shell: cmd`.

2. **XML comments `<!-- ... -->`** — `element.text` includes `<!--` and
   `-->`. A URL placed at the very start (`<!--https://...-->`) needs
   `mapping.toOriginal(0) == 4` so the highlight does not land on `<!--`.
   Identity highlights the opening bracket.

3. **YAML block comments** — several `#` lines glued together. To match
   `shell:` written on line 2 of a comment block, we need to drop `# ` from
   every line; then offsets shift by `2 * line_index`.

4. **PHP `//` single-line** — prefix `//` plus optional space. Already
   broken for `//shell: cmd` (no space): regexp matches at offset 2 in
   normalized text but document has it at offset 2 anyway, so it accidentally
   works. The hidden coupling will bite once we introduce normalization for
   PHPDoc and reuse the same code path.

5. **PHP `#` single-line and `#[...]` attributes** — `#[` is an attribute,
   not a comment, and `PsiTreeUtil.findChildrenOfAnyType(PsiComment)` already
   filters attributes out. `#` comments behave like `//`.

Out of scope: PHP/JS concatenation `"shell: " . $cmd`, heredoc/nowdoc,
template literals, `kotlin """ """`. These need a different extraction
strategy (PSI walk into string-literal parts) and a separate plan.

## Reproduction

File: `playground/1.php`

```php
<?php

/**
 * shell: echo 123
 */

/**
 * shell: echo 123
 */
// https://example.com
```

Steps:

1. Open `playground/1.php` in an IDE with the plugin installed.
2. Observe the `shell:` inlay/run-gutter on lines 4 and 8.
3. Click run. The captured value visible in the console title is
   `echo 123` — looks OK.
4. Change the second PHPDoc to:

   ```php
   /** shell: echo 123 */
   ```

   Now the regexp matches at offset 4 in normalized text. The highlight in the
   editor still lands at offset 4 inside `element.textRange` — which is
   `shell` — so it looks right *by accident* because the leading `/**` is
   exactly 3 chars and the space makes offset 4.

5. Edit it to a non-`/** */` form, e.g.:

   ```php
   /**
    *shell: echo 123
    */
   ```

   (no space after `*`). The regexp still matches, but `m.offset` now points
   to the position of `s` inside `element.text`, which is *after* the
   leading `\n * `. Identity maps that 1:1 to document offsets, which happens
   to be correct here too — because we never stripped anything. So the
   *currently visible* bug is small; the *latent* bug is that the moment we
   strip `* ` from every line to make richer matching possible, all of these
   offsets shift and Identity becomes wrong.

A clearer immediate-failure reproduction once we add stripping:

```php
/**
 * Line one
 * shell: echo 42
 * Line three
 */
```

If we normalize to `Line one\nshell: echo 42\nLine three\n`, the regexp
matches at offset 9. With Identity that lands in the middle of
`* Line one\n` in the document — visibly wrong inlay placement.

File: `playground/1.yaml`

```yaml
services:
 http:
 console:
  # shell: echo 123
  - runner
```

Only one `#` line, so identity holds. Add a second:

```yaml
  # shell: echo 123
  # https://example.com
```

PSI gives two `PsiComment` siblings, so each is handled independently and
Identity still holds per block. Identity breaks only if we choose to glue
adjacent `#` lines into one logical block (planned for YAML, see below).

## Mapping model

Requirements:

- `toOriginal(normalizedOffset)` must be cheap and correct for every offset
  the matcher can emit (start of a match and end-of-match, i.e. start+length).
- `toNormalized(originalOffset)` is currently unused by adapters, but it is
  part of the interface and useful for "caret is inside a match" checks; keep
  it.
- Must support the case where the matcher returns offsets at the very end of
  the normalized text (offset == `text.length`).
- Should round-trip exactly when applied to an offset that lies on a kept
  character.
- For offsets that fall on stripped characters (e.g. someone passes the
  position of a `*` prefix), define the behavior: clamp to the nearest kept
  character on the right.

Chosen shape: **dense piecewise-linear mapping built from a sorted list of
segments**.

```kotlin
/**
 * Maps offsets between normalized text (fed to matchers) and original PSI
 * text (used for editor coordinates).
 *
 * Built from a list of [Segment]s. Each segment describes a contiguous run
 * of characters that exists in both texts; consecutive segments may have
 * gaps in the original text (stripped decoration), but never in the
 * normalized text.
 */
class SegmentedOffsetMapping(
    private val segments: List<Segment>,
    private val normalizedLength: Int,
    private val originalLength: Int,
) : OffsetMapping {
    /**
     * @param normalizedStart  inclusive offset in normalized text
     * @param originalStart    inclusive offset in original text
     * @param length           number of chars common to both
     */
    data class Segment(val normalizedStart: Int, val originalStart: Int, val length: Int)
    // binary search by normalizedStart for toOriginal, by originalStart for toNormalized
}
```

Properties:

- Segments are sorted by `normalizedStart`; lookup is `O(log n)`.
- For a normalized offset `o` inside segment `s`:
  `toOriginal(o) = s.originalStart + (o - s.normalizedStart)`.
- For `o == normalizedLength` (end-of-text sentinel): return
  `originalLength`. Adapters use this when computing
  `startOriginal + m.value.length` — but note that the **length is taken
  from `m.value`, not from a mapped end-offset**, so we need a different
  fix (see "Length handling" below).
- For `o` in a gap between segments (only happens via `toNormalized` on
  stripped chars): clamp right to the next segment's start.

### Length handling

Today: `endOriginal = startOriginal + m.value.length`. That is wrong as soon
as the original spans more chars than the normalized value (e.g. value
contains a `\n` that was preserved but the original has `\n * ` between two
captured pieces — though for the in-scope cases this does not happen because
all stripped chars are at line starts and `(.+)` does not cross `\n`).

For Phase 1 we keep matches single-line (`(.+)` is the dot, no `\n`).
Therefore each match lies entirely inside one segment, and
`startOriginal + m.value.length` stays correct. We add an assertion in debug
builds: `require(mappingSegmentOf(start) == mappingSegmentOf(start + len))`.

For multi-line matches (future, out of scope) compute
`endOriginal = mapping.toOriginal(m.offset + m.value.length)`. Adapter code
should already be migrated to that form preemptively — it is one extra call
and keeps single-line and multi-line paths identical. **This is a required
adapter change** (see step 4).

### Why not a function or two parallel int arrays?

- Function (`(Int) -> Int`): cannot answer the inverse direction without
  scanning; serialization-unfriendly; harder to debug.
- Two parallel `IntArray`s: faster than `List<Segment>` and worth doing if
  profiling shows it, but Phase 1 prioritizes clarity. We can swap the
  backing store later without touching callers.

## Per-language strategy

Each language extractor knows its own decoration. Approach: extractor
returns the raw `PsiComment` text, then a per-language `CommentNormalizer`
strips decoration and emits both the normalized text and the segment list.

```kotlin
interface CommentNormalizer {
    fun normalize(rawText: String): NormalizedComment
}
data class NormalizedComment(
    val text: String,
    val mapping: SegmentedOffsetMapping,
)
```

The `BaseLanguageTextExtractor` no longer hard-codes `OffsetMapping.Identity`;
subclasses pass a normalizer:

```kotlin
abstract class BaseLanguageTextExtractor(
    private val normalizer: CommentNormalizer = IdentityNormalizer,
) : LanguageTextExtractor { ... }
```

### XML (`<!-- ... -->`)

Strip:

- Leading `<!--` (4 chars).
- Trailing `-->` (3 chars).
- One optional space after `<!--` and before `-->` (cosmetic; do not strip
  more than one).

CDATA (`<![CDATA[ ... ]]>`) is **not** a `PsiComment`; it is `XmlText`/
`CDATA` token. Out of scope for this iteration — extractor would need to
also look at `XmlText` nodes, separate plan.

Segments: one segment for the inner range `[4, length - 3)` (or adjusted for
the optional inner spaces).

Multi-line XML comments are common:

```xml
<!--
  shell: echo 1
  https://example.com
-->
```

No per-line decoration in XML, so a **single segment** still suffices. Leading
indentation on inner lines is preserved in the normalized text (the regexp
handles `\s*` itself).

### PHP (`/** ... */`, `/* ... */`, `//`, `#`)

PSI element type tells us which one:

- `PhpDocComment` / `PhpDocType.DOC_COMMENT`: PHPDoc, strip `*` line prefixes.
- `PhpTokenTypes.C_STYLE_COMMENT` (`/* ... */`): like PHPDoc minus the
  per-line `*` decoration (rarely decorated).
- `PhpTokenTypes.LINE_COMMENT` (`//`, `#`): strip the leading marker plus
  one optional space.

PHPDoc normalization rules:

1. Strip leading `/**` (3 chars) and any whitespace up to and including the
   first `\n` (preserve the `\n` itself as part of normalized text? — **no**,
   drop it; first content line starts at offset 0 of normalized text).
2. Strip trailing whitespace + `*/`.
3. For each interior line: strip leading `\s*\*\s?` (regex: at most one
   space after the `*` — matches `phpDocumentor` style; do not strip more
   because indentation inside `@example` blocks is significant).
4. Preserve `\n` between content lines.

Example:

```
Raw:        "/**\n * Line one\n * shell: echo 42\n * Line three\n */"
Normalized: "Line one\nshell: echo 42\nLine three"

Segments (normalizedStart, originalStart, length):
  (0,  7,  8)   // "Line one"
  (8,  15, 1)   // "\n"
  (9,  20, 14)  // "shell: echo 42"
  (23, 34, 1)   // "\n"
  (24, 39, 10)  // "Line three"
```

(Numbers illustrative; correct values come from the parser; see step 2.)

Implementation: scan the raw text line by line, tracking
`originalCursor` and `normalizedCursor`. For each line, find the boundary
between decoration and content with a single regex
`^\s*\*\s?` (anchored), emit one segment for the content slice, advance
both cursors. Newlines between lines are emitted as 1-char segments so the
matcher can still see line breaks.

`//` and `#`: strip leading `//` or `#`, then at most one space. One
segment.

### YAML (`# ...`)

Single `#` comment line: same as PHP `//`.

Block of consecutive `#` lines (same column? — yes, otherwise treat as
independent): glue into one logical block. Strip `^\s*#\s?` from each line,
preserve `\n` between them.

PSI shape: `YAMLPsiElement` produces one `PsiComment` per line. To glue
them we need to either:

- (a) walk `element.nextSibling` while it is whitespace-or-comment and the
  whitespace contains exactly one `\n`, accumulating; or
- (b) override `extract` in `YamlLanguageExtractor` to group siblings before
  delegating to the normalizer.

Choose (b) — keeps the base extractor simple. Group key: same indentation
column, contiguous (no blank line, no non-comment node between).

For Phase 1 of this plan, **do not glue** YAML comments yet — each
`PsiComment` is one block. This keeps Identity-correct unless we strip the
`#`. We *will* strip the `#` (single-line normalizer), so YAML still needs
the segmented mapping; just no cross-line concern.

### Adapter (fallback)

`AdapterLanguageExtractor` handles every other language. We do not know the
comment syntax, so we cannot strip safely. Use `IdentityNormalizer`: emit a
single segment `(0, 0, text.length)`. Behavior is identical to today.

## Implementation steps

Each step is one commit; CI must pass at every step.

### Step 1 — Introduce `SegmentedOffsetMapping` (no behavior change)

- Add `base/api/SegmentedOffsetMapping.kt` with `Segment`, binary-search
  lookup, factory `SegmentedOffsetMapping.identity(length)`.
- Add unit tests in
  `src/test/kotlin/.../base/api/SegmentedOffsetMappingTest.kt`:
  - identity round-trip;
  - single-segment with leading gap (`<!--` case);
  - multi-segment with per-line gaps (`/** * ... */` case);
  - boundary offsets (0, length, gap edges);
  - inverse direction `toNormalized` clamping behavior.
- `OffsetMapping.Identity` stays for now (back-compat).

### Step 2 — Introduce `CommentNormalizer` SPI

- Add `base/api/CommentNormalizer.kt` (interface + `NormalizedComment`
  data class).
- Add `base/normalizers/IdentityNormalizer.kt` (returns text as-is + identity
  mapping).
- Add `base/normalizers/SingleLineCommentNormalizer.kt` parameterized by
  marker pattern (`//`, `#`, `--`, ...). Strips `^\s*<marker>\s?`.
- Add `base/normalizers/CStyleBlockNormalizer.kt` for `/* ... */` and
  `/** ... */`. Constructor flag `stripStarPrefix: Boolean`.
- Add `base/normalizers/XmlCommentNormalizer.kt` for `<!-- ... -->`.
- Unit-test each normalizer with hand-written input/expected pairs **and**
  property test: for every offset `i` in normalized text,
  `original[mapping.toOriginal(i)] == normalized[i]` (when both indices are
  in range and the char is not stripped).

### Step 3 — Wire normalizer into `BaseLanguageTextExtractor`

- Change ctor to accept `CommentNormalizer` (default
  `IdentityNormalizer`).
- In `extract()`, for each `PsiComment`:
  - call `normalizer.normalize(element.text)`;
  - build `ExtractedBlock(element, originalRange, normalized.text,
    normalized.mapping)`.
- `AdapterLanguageExtractor` keeps default; behavior unchanged.

### Step 4 — Migrate adapters to map end-offsets via mapping

In `ShellFeatureAdapter` and `HttpFeatureAdapter`:

```kotlin
val startOriginal = base + block.mapping.toOriginal(m.offset)
val endOriginal   = base + block.mapping.toOriginal(m.offset + m.value.length)
```

Add assertion (debug only) that
`endOriginal - startOriginal >= m.value.length` (original may be longer due
to stripped decoration; never shorter).

### Step 5 — Per-language wiring

- `PHPLanguageExtractor`: pick normalizer by PSI token type. Easiest path:
  override `extract()` and dispatch on `element` class. Token-type-based
  registry inside `BaseLanguageTextExtractor` is overkill for three cases.
  - `PhpDocComment` -> `CStyleBlockNormalizer(stripStarPrefix = true)`
  - `PhpTokenTypes.C_STYLE_COMMENT` -> `CStyleBlockNormalizer(stripStarPrefix = false)`
  - else (line comments) -> `SingleLineCommentNormalizer("//|#")`
- `XmlLanguageExtractor`: pass `XmlCommentNormalizer` to base ctor.
- `YamlLanguageExtractor`: pass `SingleLineCommentNormalizer("#")`. **Do
  not glue** sibling comments in this step.

### Step 6 — Delete `OffsetMapping.Identity` usages from production code

Keep `OffsetMapping.Identity` as a convenience for tests and the
`IdentityNormalizer`. Grep the repo to confirm no other call sites construct
it directly outside normalizers.

### Step 7 — Optional: YAML comment gluing

Separate commit; only land if Phase 1 leaves obvious gaps. Detect
contiguous same-column `PsiComment` siblings, build one block whose
`originalRange` spans them all, run `SingleLineCommentNormalizer` line-by-line.

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
    phpdoc_single_line.expected.txt
    phpdoc_multi_line.php
    phpdoc_multi_line.expected.txt
    phpdoc_no_space_after_star.php
    phpdoc_no_space_after_star.expected.txt
  xml/
    single_line.xml
    multi_line.xml
  yaml/
    single.yaml
    multiple_siblings.yaml
```

`*.expected.txt` format (one block per record):

```
ORIGINAL:    "/** shell: echo 1 */"   # quoted, escapes visible
NORMALIZED:  "shell: echo 1"
MATCH:       "shell:" feature=shell value="echo 1"
             normalized=[0,13) original=[4,17)
```

Tests:

1. **Extractor parametrized tests** (`PhpExtractorTest`, `XmlExtractorTest`,
   `YamlExtractorTest`): load file, run extractor, assert
   `block.text == expected.normalized` and segment list matches expected
   ranges.
2. **End-to-end adapter tests**: load file, run extractor + adapter,
   assert `FeatureMatch.originalRange` matches expected document offsets.
   Use `BasePlatformTestCase` so `PsiFile` is real; assert that
   `editor.document.getText(match.originalRange) == match.value` (this is
   the invariant Identity violates today).
3. **Property test** in `SegmentedOffsetMappingTest`: random insertions
   of `*`/`#` decoration, verify round-trip on every preserved index.
4. **Regression test for `playground/1.php` scenario**: load the file,
   assert two shell matches at the expected document offsets.

CI hooks: add the testData paths to the existing `MyPluginTest` runner if
it gates on a fixture directory; otherwise no harness changes needed.

## Risks

- **Greedy regexp meets multi-segment text.** `(.+)` does not cross `\n`,
  so a match cannot today span a stripped gap. If anyone changes the
  pattern to `(.+?)` with DOTALL or `(?s)(.+)`, a match could span
  multiple segments and `m.value.length` would no longer equal
  `endOriginal - startOriginal`. Mitigation: Step 4 already routes
  end-offset through the mapping; the in-debug assertion will catch
  mismatches early.
- **PHPDoc layouts in the wild are inconsistent.** Some authors use no
  space after `*`, some use multiple, some indent `* ` deeper inside
  `@example`. The regex `^\s*\*\s?` strips at most one space, preserving
  inner indentation. Verify with at least three real-world snippets in
  testData (Symfony, Laravel, Yii style).
- **Performance on large files.** Each `PsiComment` allocates a fresh
  `SegmentedOffsetMapping`. For a file with thousands of comments this is
  still a few hundred KB of `Segment` objects — acceptable. If profiling
  shows pressure, switch the backing store to two parallel `IntArray`s
  and intern `IdentityMapping` for single-segment cases.
- **YAML gluing (Step 7) interacts with the inlay provider.** Today the
  inlay is attached per `PsiComment`. A glued block spans multiple
  comments; the provider would need to either pick the first comment as
  the anchor or be taught about multi-comment blocks. Defer until needed.
- **PSI quirks**: `PhpDocComment.text` sometimes contains `\r\n` on
  Windows-checked-out files. Normalizer must treat `\r\n` and `\n` as a
  single line terminator for the purpose of stripping the next `* `
  prefix, but preserve the original bytes in the segment so document
  offsets stay correct. Cover with a Windows-style fixture.
- **Inverse direction**: `toNormalized` on a stripped char (e.g. caret
  inside `<!--`) returns the next kept index. Document this contract;
  callers must not assume the result equals input.
- **`OffsetMapping.Identity` deprecation**: leave a `@Deprecated` hint
  pointing at `IdentityNormalizer` to discourage new call sites.
