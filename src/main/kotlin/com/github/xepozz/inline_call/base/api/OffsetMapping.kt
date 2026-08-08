package com.github.xepozz.inline_call.base.api

/**
 * Maps between normalized text offsets and original PSI text offsets.
 *
 * The production implementation is [SegmentedOffsetMapping]; the legacy
 * [Identity] singleton is kept only for binary compatibility and for
 * tests that need a trivial mapping with no length context.
 */
interface OffsetMapping {
    fun toOriginal(normalizedOffset: Int): Int
    fun toNormalized(originalOffset: Int): Int

    @Deprecated(
        "Use SegmentedOffsetMapping.identity(length) or IdentityNormalizer instead",
        ReplaceWith(
            "SegmentedOffsetMapping.identity(length)",
            "com.github.xepozz.inline_call.base.api.SegmentedOffsetMapping",
        ),
    )
    object Identity : OffsetMapping {
        override fun toOriginal(normalizedOffset: Int) = normalizedOffset
        override fun toNormalized(originalOffset: Int) = originalOffset
    }
}