package com.github.xepozz.call.base.wrappers

import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.api.WrapperFactory

class ConsoleWrapperFactory : WrapperFactory {
    override fun supports(featureGenerator: FeatureGenerator): Boolean =
        featureGenerator.id == "shell" || featureGenerator.id == "http"

    override fun create(match: FeatureMatch): Wrapper = ConsoleWrapper(match.block.element.project)
}
