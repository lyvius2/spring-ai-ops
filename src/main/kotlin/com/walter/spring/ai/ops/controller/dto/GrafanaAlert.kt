package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Individual alert entry within a Grafana Alerting webhook payload")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GrafanaAlert(
    @Schema(description = "Alert status (firing / resolved)", example = "firing")
    val status: String,
    @Schema(description = "Prometheus / Loki labels attached to this alert")
    val labels: Map<String, String>,
    @Schema(description = "Alert annotations (e.g. summary, description)")
    val annotations: Map<String, String>,
    @Schema(description = "ISO-8601 timestamp when the alert started firing", example = "2024-01-01T00:00:00Z")
    val startsAt: String,
    @Schema(description = "ISO-8601 timestamp when the alert resolved; '0001-...' means still firing", example = "0001-01-01T00:00:00Z")
    val endsAt: String,
    @Schema(description = "URL to the Prometheus rule that generated this alert")
    val generatorURL: String,
    @Schema(description = "Unique fingerprint of the alert")
    val fingerprint: String,
    @Schema(description = "URL to silence this alert in Grafana", nullable = true)
    val silenceURL: String?,
    @Schema(description = "URL to the related Grafana dashboard", nullable = true)
    val dashboardURL: String?,
    @Schema(description = "URL to the related Grafana panel", nullable = true)
    val panelURL: String?,
    @Schema(description = "URL to the alert image snapshot", nullable = true)
    val imageURL: String?,
    @Schema(description = "Metric values at the time of the alert", nullable = true)
    val values: Map<String, Double>?,
    @Schema(description = "Human-readable string representation of metric values", nullable = true)
    val valueString: String?,
) {
    fun isFiring(): Boolean = status == "firing"

    fun alertName(): String = labels["alertname"] ?: "unknown"

    fun lokiLabels(): Map<String, String> = labels.filterKeys { it in LOKI_LABEL_KEYS }

    /** startsAt 기준으로 버퍼를 뺀 Loki 조회 시작 시각 (Unix nano 문자열) */
    fun lokiStartNano(bufferMinutes: Long = 5): String {
        val instant = java.time.Instant.parse(startsAt)
            .minusSeconds(bufferMinutes * 60)
        return instant.toEpochMilli().times(1_000_000L).toString()
    }

    /** endsAt이 zero-value("0001-...")이면 현재 시각 사용 */
    fun lokiEndNano(): String {
        val instant = if (endsAt.startsWith("0001-")) {
            java.time.Instant.now()
        } else {
            java.time.Instant.parse(endsAt)
        }
        return instant.toEpochMilli().times(1_000_000L).toString()
    }

    companion object {
        private val LOKI_LABEL_KEYS = setOf(
            "job", "instance", "namespace", "pod", "container",
            "service_name", "app", "cluster", "env", "environment",
            "application"
        )
    }
}
