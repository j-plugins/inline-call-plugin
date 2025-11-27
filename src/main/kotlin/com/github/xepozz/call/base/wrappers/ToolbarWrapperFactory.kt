package com.github.xepozz.call.base.wrappers

import com.github.xepozz.call.base.api.FeatureGenerator
import com.github.xepozz.call.base.api.FeatureMatch
import com.github.xepozz.call.base.api.Wrapper
import com.github.xepozz.call.base.api.WrapperFactory

class ToolbarWrapperFactory : WrapperFactory {
    override fun supports(featureGenerator: FeatureGenerator): Boolean = true

    override fun create(match: FeatureMatch): Wrapper = ToolbarWrapper(match.block.element.project)
}
