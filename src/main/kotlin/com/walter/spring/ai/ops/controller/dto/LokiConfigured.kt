package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class LokiConfigured(
    @JsonProperty("isConfigured")
    val isConfigured: Boolean
)