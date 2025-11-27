package com.github.xepozz.call.base.api

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Factory for wrappers per feature.
 */
interface WrapperFactory {
    fun supports(featureId: String): Boolean
    fun create(match: FeatureMatch): Wrapper

    companion object {
        val EP_NAME: ExtensionPointName<WrapperFactory> =
            ExtensionPointName.Companion.create("com.github.xepozz.call.wrapperFactory")
    }
}