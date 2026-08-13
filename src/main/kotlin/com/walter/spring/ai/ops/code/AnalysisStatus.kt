package com.walter.spring.ai.ops.code

enum class AnalysisStatus(
    val description: String,
) {
    ANALYZED("LLM analysis completed"),
    SKIPPED_NO_OBSERVABILITY("Loki and Prometheus are not configured"),
    SKIPPED_OBSERVABILITY_ERROR("Failed to fetch logs and metrics due to a connection error"),
}