package com.walter.spring.ai.ops.connector.dto

data class LokiData(
    val resultType: String = "",
    val result: List<LokiStream> = emptyList(),
)
