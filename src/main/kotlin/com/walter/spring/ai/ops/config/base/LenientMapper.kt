package com.walter.spring.ai.ops.config.base

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper

class LenientMapper(baseMapper: ObjectMapper) : ObjectMapper(baseMapper) {
    init {
        configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
    }
}
