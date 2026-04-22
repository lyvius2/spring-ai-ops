package com.walter.spring.ai.ops.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MapperConfig {
    @Bean("lenientMapper")
    fun lenientMapper(objectMapper: ObjectMapper): ObjectMapper {
        return objectMapper.copy().apply {
            configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
        }
    }
}