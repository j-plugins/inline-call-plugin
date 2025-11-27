package com.github.xepozz.call.implementation.wrappers

import com.github.xepozz.call.implementation.api.FeatureMatch
import com.github.xepozz.call.implementation.api.Wrapper
import com.github.xepozz.call.implementation.api.WrapperFactory

class ConsoleWrapperFactory : WrapperFactory {
    override fun supports(featureId: String): Boolean = featureId == "shell" || featureId == "http"

    override fun create(match: FeatureMatch): Wrapper = ConsoleWrapper(match.block.element.project)
}
