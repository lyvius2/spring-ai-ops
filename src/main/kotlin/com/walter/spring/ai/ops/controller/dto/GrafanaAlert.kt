package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GrafanaAlert(
    val status: String,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val startsAt: String,
    val endsAt: String,
    val generatorURL: String,
    val fingerprint: String,
    val silenceURL: String?,
    val dashboardURL: String?,
    val panelURL: String?,
    val imageURL: String?,
    val values: Map<String, Double>?,
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
        )
    }
}
