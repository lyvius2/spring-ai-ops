package com.walter.spring.ai.ops.connector.dto

data class LokiStream(
    val stream: Map<String, String> = emptyMap(),
    val values: List<List<String>> = emptyList(),
)
