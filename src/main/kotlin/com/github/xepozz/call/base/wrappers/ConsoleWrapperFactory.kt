package com.github.xepozz.call.base.wrappers

import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.api.WrapperFactory

class ConsoleWrapperFactory : WrapperFactory {
    override fun supports(featureId: String): Boolean = featureId == "shell" || featureId == "http"

    override fun create(match: FeatureMatch): Wrapper = ConsoleWrapper(match.block.element.project)
}
