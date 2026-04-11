package com.walter.spring.ai.ops.connector.dto

data class LokiQueryInquiry(
    val query: String,
    val start: String,
    val end: String,
    val limit: Int = 100,
    val direction: String = "backward",
)