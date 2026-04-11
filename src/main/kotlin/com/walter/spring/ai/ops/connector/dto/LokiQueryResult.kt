package com.walter.spring.ai.ops.connector.dto

data class LokiQueryResult(
    val status: String = "",
    val data: LokiData? = null,
    val errorMessage: String = "",
)