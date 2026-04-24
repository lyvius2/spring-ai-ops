package com.walter.spring.ai.ops.code

enum class ObservabilityProvider(
    val contentType: String,
    val displayName: String,
    val apiUrl: String,
) {
    LOKI("log", "Loki", "http://loki:3100"),
    PROMETHEUS("metric", "Prometheus", "http://prometheus:9090");
}