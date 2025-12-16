package com.github.xepozz.inline_call.base.api

/**
 * Maps between normalized text offsets and original PSI text offsets.
 * For Phase 1 we keep identity mapping; extractors may upgrade it later.
 */
interface OffsetMapping {
    fun toOriginal(normalizedOffset: Int): Int
    fun toNormalized(originalOffset: Int): Int

    object Identity : OffsetMapping {
        override fun toOriginal(normalizedOffset: Int) = normalizedOffset
        override fun toNormalized(originalOffset: Int) = originalOffset
    }
}