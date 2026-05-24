package com.github.xepozz.inline_call.base.inlay

import com.github.xepozz.inline_call.base.api.FeatureGenerator
import com.github.xepozz.inline_call.base.api.LanguageTextExtractor
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.utils.inlays.InlayHintsProviderTestCase

/**
 * Verifies that [ExecutionInlayProvider] actually renders inlay hints
 * next to `shell:` comments and `https://...` comments in real files.
 *
 * Uses the platform's [InlayHintsProviderTestCase.doTestProvider] —
 * the test content embeds inline hint expectations as `/*<# ... #>*/`,
 * the framework strips them, runs the provider through a real
 * FactoryInlayHintsCollector and renders the presentations into a
 * text dump. Actual vs expected dumps are compared char-by-char.
 *
 * The dump format the platform uses: an icon presentation renders
 * as `<image>` and a `roundWithBackground` wraps its content with
 * `[ ... ]`. So the Run button (icon + " Run " with rounded background)
 * comes out as `[<image>  Run ]` — two spaces because the inner
 * `text(" Run ")` already has a leading space and the icon precedes it.
 *
 * Headless. No Swing rendering required.
 */
@Suppress("UnstableApiUsage")
class ExecutionInlayProviderTest : InlayHintsProviderTestCase() {

    fun `test shell run button appears next to shell comment in YAML`() {
        val content = """
            services:
              # /*<# [<image>  Run ] #>*/shell: echo 123
        """.trimIndent()
        doTestProvider(
            "test.yaml",
            content,
            ExecutionInlayProvider(),
            NoSettings(),
        )
    }

    fun `test http run button appears next to URL in YAML`() {
        val content = """
            apis:
              # /*<# [<image>  Run ] #>*/https://example.com
        """.trimIndent()
        doTestProvider(
            "test.yaml",
            content,
            ExecutionInlayProvider(),
            NoSettings(),
        )
    }

    fun `test no inlay in YAML file without matchable comments`() {
        val content = """
            services:
              # just a regular comment
              # nothing here either
        """.trimIndent()
        doTestProvider(
            "test.yaml",
            content,
            ExecutionInlayProvider(),
            NoSettings(),
        )
    }

    fun `test shell and http inlays coexist on adjacent lines`() {
        val content = """
            # /*<# [<image>  Run ] #>*/shell: echo one
            # /*<# [<image>  Run ] #>*/https://api.example.com/two
        """.trimIndent()
        doTestProvider(
            "test.yaml",
            content,
            ExecutionInlayProvider(),
            NoSettings(),
        )
    }

    /**
     * Smoke check: the provider's underlying extractor + feature chain
     * actually finds the comment block and produces a FeatureMatch for
     * "shell:". If this passes, the chain that feeds the inlay sink is
     * intact even if the rendering test happens to be off by a
     * presentation-tree detail.
     */
    fun `test extractor + feature chain produces a match for shell comment`() {
        val yamlType = FileTypeManager.getInstance().getFileTypeByExtension("yaml")
        val psiFile = myFixture.configureByText(yamlType, "# shell: echo plumbing\n")

        val extractors = LanguageTextExtractor.getApplicable(psiFile)
        assertTrue("at least one extractor must be applicable for YAML", extractors.isNotEmpty())

        val blocks = extractors.flatMap { it.extract(psiFile) }
        assertFalse("the comment must produce at least one ExtractedBlock", blocks.isEmpty())

        val features = FeatureGenerator.getApplicable(project)
        assertTrue("shell feature must be enabled", features.any { it.id == "shell" })

        // Iterate every extractor's blocks against the shell feature; collect
        // unique matches by value. Multiple extractors may overlap (yaml
        // language-specific + fallback adapter); we just want at least one
        // distinct match with the right command.
        val shellFeature = features.first { it.id == "shell" }
        val shellMatches = blocks.flatMap { shellFeature.match(it, project) }
        assertFalse("shell feature must match the comment", shellMatches.isEmpty())
        assertTrue(
            "shell feature must extract 'echo plumbing' from the comment, got: ${shellMatches.map { it.value }}",
            shellMatches.any { it.value == "echo plumbing" },
        )
    }
}
