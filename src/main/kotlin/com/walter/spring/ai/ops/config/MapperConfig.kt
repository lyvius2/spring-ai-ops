package com.walter.spring.ai.ops.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.walter.spring.ai.ops.config.base.LenientMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
class MapperConfig {
    @Bean
    @Primary
    fun objectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper =
        builder.build()

    @Bean
    fun lenientMapper(objectMapper: ObjectMapper): LenientMapper =
        LenientMapper(objectMapper)
}