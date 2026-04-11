package com.walter.spring.ai.ops.connector.dto

data class LokiQueryResult(
    val status: String = "",
    val data: Data? = null,
    val errorMessage: String = "",
) {
    data class Data(
        val resultType: String = "",
        val result: List<Stream> = emptyList(),
    )

    data class Stream(
        val stream: Map<String, String> = emptyMap(),
        val values: List<List<String>> = emptyList(),
    )
}