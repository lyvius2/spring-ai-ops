package com.walter.spring.ai.ops.controller.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GrafanaAlertingRequest(
    val receiver: String,
    val status: String,
    val orgId: Long,
    val alerts: List<GrafanaAlert>,
    val groupLabels: Map<String, String>,
    val commonLabels: Map<String, String>,
    val commonAnnotations: Map<String, String>,
    val externalURL: String,
    val version: String,
    val groupKey: String,
    val truncatedAlerts: Int,
    val title: String,
    val state: String,
    val message: String,
) {
    fun isFiring(): Boolean = status == "firing"
    fun isResolved(): Boolean = status == "resolved"
    fun createAlertSectionPrompt(): String {
        val alert = this.alerts.firstOrNull { it.isFiring() } ?: this.alerts.firstOrNull()
        val request = this
        val alertSection = buildString {
            appendLine("## Alert Information")
            appendLine("- Title: ${request.title}")
            appendLine("- Status: ${request.status}")
            appendLine("- Message: ${request.message}")
            if (alert != null) {
                appendLine("- Alert Name: ${alert.alertName()}")
                appendLine("- Started At: ${alert.startsAt}")
                if (alert.annotations.isNotEmpty()) {
                    appendLine("- Annotations: ${alert.annotations.entries.joinToString { "${it.key}=${it.value}" }}")
                }
                if (alert.labels.isNotEmpty()) {
                    appendLine("- Labels: ${alert.labels.entries.joinToString { "${it.key}=${it.value}" }}")
                }
            }
        }
        return alertSection
    }
}
