package com.github.xepozz.call.base.api

import com.intellij.openapi.util.TextRange

/**
 * A matched feature occurrence within an extracted block.
 */
data class FeatureMatch(
    val featureId: String,
    val block: ExtractedBlock,
    val value: String,
    val normalizedRange: TextRange,
    val originalRange: TextRange,
)