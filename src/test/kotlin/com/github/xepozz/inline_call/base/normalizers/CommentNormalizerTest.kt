package com.github.xepozz.inline_call.base.normalizers

import com.github.xepozz.inline_call.base.api.CommentNormalizer
import com.github.xepozz.inline_call.base.api.NormalizedComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the four [CommentNormalizer] implementations.
 * Each test asserts both the normalized text and the "preserved index"
 * property: for every i in normalized.text.indices,
 * normalized.text[i] == rawText[mapping.toOriginal(i)].
 */
class CommentNormalizerTest {

    @Test
    fun `IdentityNormalizer is a no-op`() {
        val raw = "hello world"
        val n = IdentityNormalizer.normalize(raw)
        assertEquals(raw, n.text)
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `SingleLine strips slashes and one space`() {
        val raw = "// echo 1"
        val n = SingleLineCommentNormalizer("//|#").normalize(raw)
        assertEquals("echo 1", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(3, n.mapping.toOriginal(0)) // 'e'
        assertEquals(raw.length, n.mapping.toOriginal(n.text.length))
    }

    @Test
    fun `SingleLine strips hash and one space`() {
        val raw = "# shell: echo 1"
        val n = SingleLineCommentNormalizer("#").normalize(raw)
        assertEquals("shell: echo 1", n.text)
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `SingleLine respects leading indentation`() {
        val raw = "  # shell: foo"
        val n = SingleLineCommentNormalizer("#").normalize(raw)
        assertEquals("shell: foo", n.text)
        assertEquals(4, n.mapping.toOriginal(0)) // 's' after "  # "
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `SingleLine with no-space marker keeps everything after slash`() {
        val raw = "//cmd"
        val n = SingleLineCommentNormalizer("//|#").normalize(raw)
        assertEquals("cmd", n.text)
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `XmlComment strips brackets and optional spaces`() {
        val raw = "<!-- https://example.com -->"
        val n = XmlCommentNormalizer.normalize(raw)
        assertEquals("https://example.com", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(5, n.mapping.toOriginal(0))
    }

    @Test
    fun `XmlComment with no inner spaces is identity on content`() {
        val raw = "<!--https://example.com-->"
        val n = XmlCommentNormalizer.normalize(raw)
        assertEquals("https://example.com", n.text)
        assertEquals(4, n.mapping.toOriginal(0))
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `XmlComment empty body`() {
        val raw = "<!---->"
        val n = XmlCommentNormalizer.normalize(raw)
        assertEquals("", n.text)
        assertEquals(7, n.mapping.toOriginal(0))
    }

    @Test
    fun `CStyleBlock single-line block strips opener and trailing whitespace`() {
        val raw = "/* shell: echo 1 */"
        val n = CStyleBlockNormalizer(stripStarPrefix = false).normalize(raw)
        assertEquals("shell: echo 1", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(3, n.mapping.toOriginal(0))
    }

    @Test
    fun `CStyleBlock single-line PHPDoc with stripStarPrefix`() {
        val raw = "/** shell: echo 1 */"
        val n = CStyleBlockNormalizer(stripStarPrefix = true).normalize(raw)
        assertEquals("shell: echo 1", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(4, n.mapping.toOriginal(0))
    }

    @Test
    fun `CStyleBlock multi-line PHPDoc strips per-line star prefix`() {
        // raw[7]='L', raw[15]='\n', raw[19]='s', total length 38.
        val raw = "/**\n * Line one\n * shell: echo 42\n */"
        val n = CStyleBlockNormalizer(stripStarPrefix = true).normalize(raw)
        assertEquals("Line one\nshell: echo 42", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(7, n.mapping.toOriginal(0))   // 'L'
        assertEquals(15, n.mapping.toOriginal(8))  // '\n'
        assertEquals(19, n.mapping.toOriginal(9))  // 's'
        // End sentinel: position right after last preserved char ('2').
        // Last segment is (9, 19, 14) -> 33 (original `\n */` starts here).
        assertEquals(33, n.mapping.toOriginal(n.text.length))
    }

    @Test
    fun `CStyleBlock multi-line without stripStarPrefix preserves stars`() {
        val raw = "/*\n * hello\n */"
        val n = CStyleBlockNormalizer(stripStarPrefix = false).normalize(raw)
        // Without star-stripping, each interior line keeps its leading
        // whitespace and `*`. Trailing decoration-only line is dropped.
        assertEquals(" * hello", n.text)
        assertPreservedIndices(raw, n)
    }

    @Test
    fun `CStyleBlock CRLF treated as single line terminator`() {
        val raw = "/**\r\n * Line one\r\n * shell: echo 42\r\n */"
        val n = CStyleBlockNormalizer(stripStarPrefix = true).normalize(raw)
        assertEquals("Line one\nshell: echo 42", n.text)
        assertPreservedIndices(raw, n)
        // 'L' lives at offset: "/**\r\n " = 0..5; '*' at 5, ' ' at 6, 'L' at 7? Let's count.
        // '/'=0 '*'=1 '*'=2 '\r'=3 '\n'=4 ' '=5 '*'=6 ' '=7 'L'=8
        assertEquals(8, n.mapping.toOriginal(0))
    }

    @Test
    fun `CStyleBlock PHPDoc with no space after star still works`() {
        // raw[5]='*', raw[6]='s' (no space). Should map normalized 0 -> 6.
        val raw = "/**\n *shell: echo 1\n */"
        val n = CStyleBlockNormalizer(stripStarPrefix = true).normalize(raw)
        assertEquals("shell: echo 1", n.text)
        assertPreservedIndices(raw, n)
        assertEquals(6, n.mapping.toOriginal(0))
    }

    @Test
    fun `CStyleBlock preserves indentation inside content`() {
        // After the per-line `* ` prefix is stripped, only one space is
        // consumed; further indentation stays as content.
        val raw = "/**\n *   indented\n */"
        val n = CStyleBlockNormalizer(stripStarPrefix = true).normalize(raw)
        assertEquals("  indented", n.text)
        assertPreservedIndices(raw, n)
    }

    private fun assertPreservedIndices(raw: String, n: NormalizedComment) {
        for (i in n.text.indices) {
            val o = n.mapping.toOriginal(i)
            assertTrue("toOriginal($i)=$o out of bounds for raw length ${raw.length}", o in raw.indices)
            assertEquals(
                "normalized[$i] (${n.text[i]}) != raw[toOriginal($i)=$o] (${raw[o]})",
                n.text[i],
                raw[o],
            )
        }
    }
}
