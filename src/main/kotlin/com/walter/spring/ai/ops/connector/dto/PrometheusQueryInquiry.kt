package com.walter.spring.ai.ops.connector.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Request DTO for Prometheus range query API.
 * Maps to GET /api/v1/query_range parameters.
 */
data class PrometheusQueryInquiry(
    /** PromQL expression to evaluate */
    val query: String,
    /** Start of the query range (Unix timestamp or RFC3339) */
    val start: String,
    /** End of the query range (Unix timestamp or RFC3339) */
    val end: String,
    /** Query resolution step (e.g. "15s", "1m") */
    val step: String = "15s",
)

